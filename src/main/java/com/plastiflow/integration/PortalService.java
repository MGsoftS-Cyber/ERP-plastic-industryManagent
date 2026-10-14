package com.plastiflow.integration;

import static com.plastiflow.integration.Requests.*;

import com.plastiflow.shared.BusinessException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class PortalService {
  final IntegrationStore s;

  PortalService(IntegrationStore s) {
    this.s = s;
  }

  Map<String, Object> partner(Partner r) {
    s.lock();
    UUID id = s.id();
    s.db.update(
        "insert into int_partner values(?,?,?,?,?,true)",
        id,
        r.code(),
        r.name(),
        r.kind().name(),
        r.partyReference());
    s.audit(id, "PARTNER_CREATED", r);
    return Map.of("id", id);
  }

  void user(UUID partner, PartnerUser r) {
    s.lock();
    s.one("select * from int_partner where id=? and active=true", partner);
    s.db.update("insert into int_partner_user values(?,?,true)", r.principal(), partner);
    s.audit(partner, "PORTAL_USER_BOUND", r);
  }

  void revoke(UUID id) {
    s.lock();
    s.one("select * from int_partner where id=?", id);
    s.db.update("update int_partner set active=false where id=?", id);
    s.audit(id, "PARTNER_REVOKED", Map.of());
  }

  void revokeUser(String principal) {
    s.lock();
    var u = s.one("select * from int_partner_user where principal=?", principal);
    s.db.update("update int_partner_user set active=false where principal=?", principal);
    s.audit((UUID) u.get("partner_id"), "PORTAL_USER_REVOKED", Map.of("principal", principal));
  }

  void publish(UUID product) {
    s.lock();
    s.one("select * from catalog_product where id=? and active=true", product);
    s.db.update("insert into int_public_product values(?)", product);
    s.audit(product, "PRODUCT_PUBLISHED", Map.of());
  }

  void unpublish(UUID product) {
    s.lock();
    s.db.update("delete from int_public_product where product_id=?", product);
    s.audit(product, "PRODUCT_UNPUBLISHED", Map.of());
  }

  Map<String, Object> binding() {
    var rows =
        s.db.queryForList(
            "select p.* from int_partner_user u join int_partner p on p.id=u.partner_id where"
                + " u.principal=? and u.active=true and p.active=true",
            s.actor());
    if (rows.isEmpty())
      throw new BusinessException(
          HttpStatus.FORBIDDEN, "PORTAL_DISABLED", "No active partner binding for this account.");
    return rows.get(0);
  }

  @Transactional(readOnly = true)
  Map<String, Object> overview() {
    var p = binding();
    return Map.of(
        "partner",
        Map.of("code", p.get("code"), "name", p.get("name"), "kind", p.get("kind")),
        "products",
        s.db.queryForList(
            "select p.id,p.sku,p.name,p.kind,p.unit from int_public_product v join catalog_product"
                + " p on p.id=v.product_id where p.active=true order by p.sku limit 200"),
        "requests",
        s.db.queryForList(
            "select id,kind,reference,description,lines,state,reply,created_at from"
                + " int_portal_request where partner_id=? order by created_at desc limit 200",
            p.get("id")),
        "invoices",
        s.db.queryForList(
            "select i.id,i.code,i.currency,i.net,i.tax,i.total,i.due_on,i.total-coalesce((select"
                + " sum(amount) from fin_payment where invoice_id=i.id),0) outstanding from"
                + " fin_invoice i where i.party_reference=? and i.kind=? and i.state='POSTED' order"
                + " by i.due_on desc limit 200",
            p.get("party_reference"),
            "CUSTOMER".equals(p.get("kind")) ? "SALE" : "PURCHASE"));
  }

  Map<String, Object> request(PortalRequest r) {
    s.lock();
    var p = binding();
    var prior = s.db.queryForList("select * from int_portal_request where id=?", r.eventId());
    if (!prior.isEmpty()) {
      var old = prior.get(0);
      s.require(
          p.get("id").equals(old.get("partner_id"))
              && r.kind().name().equals(old.get("kind"))
              && r.reference().equals(old.get("reference"))
              && r.description().equals(old.get("description"))
              && s.decode((String) old.get("lines")).equals(s.decode(s.encode(r.lines()))),
          "EVENT_CONFLICT",
          "Request ID already has another payload or partner.");
      return ownRequest(r.eventId());
    }
    s.require(
        r.kind() == RequestKind.DELIVERY_NOTICE
            || ("CUSTOMER".equals(p.get("kind")) && r.kind() == RequestKind.SALES_REQUEST)
            || ("SUPPLIER".equals(p.get("kind")) && r.kind() == RequestKind.SUPPLY_OFFER),
        "PARTNER_KIND",
        "Request kind is not allowed for this partner type.");
    s.require(
        r.kind() == RequestKind.DELIVERY_NOTICE || !r.lines().isEmpty(),
        "REQUEST_LINES",
        "Sales requests and supply offers need at least one product.");
    var seen = new HashSet<UUID>();
    for (var line : r.lines()) {
      s.require(
          seen.add(line.productId()), "DUPLICATE_PRODUCT", "Combine duplicate product lines.");
      var product =
          s.one(
              "select p.* from catalog_product p join int_public_product v on v.product_id=p.id"
                  + " where p.id=? and p.active=true",
              line.productId());
      if ("PIECE".equals(product.get("unit")))
        s.require(
            line.quantity().stripTrailingZeros().scale() <= 0,
            "WHOLE_PIECES",
            "Piece quantities must be whole numbers.");
    }
    s.db.update(
        "insert into int_portal_request values(?,?,?,?,?,?,'SUBMITTED',null,?,?)",
        r.eventId(),
        p.get("id"),
        r.kind().name(),
        r.reference(),
        r.description(),
        s.encode(r.lines()),
        s.actor(),
        s.time(Instant.now()));
    s.audit(r.eventId(), "PORTAL_REQUEST_SUBMITTED", Map.of("partner", p.get("id")));
    return ownRequest(r.eventId());
  }

  @Transactional(readOnly = true)
  Map<String, Object> ownRequest(UUID id) {
    var p = binding();
    return s.one(
        "select id,kind,reference,description,lines,state,reply,created_at from int_portal_request"
            + " where id=? and partner_id=?",
        id,
        p.get("id"));
  }

  void decide(UUID id, Decision r) {
    s.lock();
    var request = s.one("select * from int_portal_request where id=? for update", id);
    s.require(
        "SUBMITTED".equals(request.get("state")), "REQUEST_DECIDED", "Request already decided.");
    s.db.update(
        "update int_portal_request set state=?,reply=? where id=?",
        r.accepted() ? "ACKNOWLEDGED" : "DECLINED",
        r.notes(),
        id);
    s.audit(id, "PORTAL_REQUEST_DECIDED", r);
  }
}
