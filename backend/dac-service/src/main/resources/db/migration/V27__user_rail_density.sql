-- How large the rail is drawn, chosen by the person using it.
--
-- 'comfortable' is the rail as it has always been -- tall rows, large icons --
-- and stays the default. 'compact' is the narrower, shorter one for somebody
-- who would rather give the width to the page. Like the order and the hidden
-- sections beside it, it is a preference about a menu and grants nothing.
ALTER TABLE user_rail
    ADD COLUMN density text NOT NULL DEFAULT 'comfortable'
        CHECK (density IN ('comfortable', 'compact'));
