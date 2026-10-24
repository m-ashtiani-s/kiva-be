-- The change log knows the blog's posts (completion roadmap, phase 15, decision 17): BLOG_POST joins the contract's
-- AuditEntityType values that V21 lists. V40 was committed without it (step 15.1), so it comes as a migration of its own.
ALTER TABLE auditlog_tbl
    DROP CONSTRAINT "auditlog_tbl_entityType_check",
    ADD CONSTRAINT "auditlog_tbl_entityType_check" CHECK ("entityType" IN ('PRODUCT', 'VARIANT', 'INVENTORY', 'MEDIA', 'CATEGORY',
                                                                         'COLOR', 'MATERIAL', 'ORDER', 'RETURN', 'REFUND',
                                                                         'DISCOUNT_CODE', 'CAMPAIGN', 'BANNER', 'ANNOUNCEMENT',
                                                                         'TESTIMONIAL', 'FAQ', 'PAGE', 'SETTINGS', 'REVIEW',
                                                                         'CONTACT_MESSAGE', 'USER', 'BLOG_POST'));
