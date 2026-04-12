-- ============================================================
-- Fix limit_profiles table schema
-- 
-- Purpose: Add missing 'description' column if table exists without it
-- ============================================================

-- Check if limit_profiles table exists and add description column if missing
DO $$
BEGIN
    -- Check if table exists
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'limit_profiles') THEN
        -- Check if description column exists
        IF NOT EXISTS (SELECT FROM information_schema.columns 
                       WHERE table_name = 'limit_profiles' 
                       AND column_name = 'description') THEN
            -- Add description column
            ALTER TABLE limit_profiles ADD COLUMN description text;
            RAISE NOTICE 'Added description column to limit_profiles table';
        ELSE
            RAISE NOTICE 'Description column already exists in limit_profiles table';
        END IF;
    ELSE
        RAISE NOTICE 'limit_profiles table does not exist yet - will be created by V7';
    END IF;
END $$;