-- Run as ERP user ONLY if you already created the old tables (first install: skip this file)
DROP TABLE erp_pending_action PURGE;
DROP TABLE erp_stock_tx PURGE;
DROP TABLE erp_inbound_plan PURGE;
DROP TABLE erp_product PURGE;
