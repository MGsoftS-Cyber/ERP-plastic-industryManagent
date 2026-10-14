CREATE TABLE catalog_audit (id UUID PRIMARY KEY, product_id UUID NOT NULL REFERENCES catalog_product(id), action VARCHAR(80) NOT NULL, actor VARCHAR(160) NOT NULL, details TEXT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL);
CREATE INDEX ix_catalog_audit_product_time ON catalog_audit(product_id,created_at);
CREATE INDEX ix_mfg_output_time_order ON mfg_output(created_at,order_id);
CREATE INDEX ix_mfg_lot_material_quality ON mfg_lot(material_id,quality);
CREATE INDEX ix_schedule_worker_time ON mfg_schedule(employee_id,starts_at,ends_at);
CREATE INDEX ix_schedule_mold_time ON mfg_schedule(mold_id,starts_at,ends_at);
CREATE INDEX ix_payroll_employee_period ON wf_period_employee(employee_id,period_id);
CREATE INDEX ix_work_order_asset_state ON ops_work_order(asset_id,state);
CREATE INDEX ix_work_order_machine_state ON ops_work_order(machine_id,state);
CREATE INDEX ix_invoice_party_state ON fin_invoice(party_reference,kind,state);
CREATE INDEX ix_payment_invoice ON fin_payment(invoice_id);
CREATE INDEX ix_ai_owner_created ON int_ai_job(created_by,created_at);
ALTER TABLE wf_adjustment ADD COLUMN created_by VARCHAR(160);
CREATE INDEX ix_payroll_audit_entity_action ON wf_audit(entity_id,action,created_at);
UPDATE wf_adjustment SET created_by=(SELECT a.actor FROM wf_audit a WHERE a.entity_id=wf_adjustment.id AND a.action='ADJUSTMENT_REQUESTED' ORDER BY a.created_at,a.id LIMIT 1);

ALTER TABLE mfg_order ADD COLUMN aborted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE mfg_order ADD COLUMN abort_reason VARCHAR(1000);
ALTER TABLE mfg_maintenance_block ADD COLUMN cancelled BOOLEAN NOT NULL DEFAULT FALSE;
