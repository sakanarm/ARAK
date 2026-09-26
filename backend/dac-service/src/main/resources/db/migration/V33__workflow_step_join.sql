-- Stages that share a step can pass together (every one must approve) or on
-- their own (any one approving passes the step). A workflow keeps the choice
-- on each stage in its jsonb; a request keeps its own copy per stage, as it
-- keeps the rest of the route it was asked on.
ALTER TABLE access_request_stage
    ADD COLUMN step_join text NOT NULL DEFAULT 'ALL' CHECK (step_join IN ('ALL', 'ANY'));
