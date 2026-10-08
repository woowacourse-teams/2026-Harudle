-- Explicit maintenance operation, never discovered by Flyway's db/migration scan.
-- Load this definition, then CALL it in the same session with autocommit enabled.
-- pg_temp keeps the procedure local to this connection; no permanent schema object.
CREATE OR REPLACE PROCEDURE pg_temp.backfill_user_profile_images(batch_size INTEGER DEFAULT 1000)
LANGUAGE plpgsql
AS $$
DECLARE
    batch_ids UUID[];
    last_user_id UUID;
    affected_rows INTEGER;
    total_rows BIGINT := 0;
BEGIN
    IF batch_size IS NULL OR batch_size < 1 OR batch_size > 10000 THEN
        RAISE EXCEPTION 'batch_size must be between 1 and 10000' USING ERRCODE = '22023';
    END IF;

    LOOP
        SELECT ARRAY(
            SELECT member.id
            FROM public.users AS member
            WHERE (last_user_id IS NULL OR member.id > last_user_id)
              AND member.profile_image_code IS NULL
              AND member.deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM public.guest_sessions AS guest
                  WHERE guest.guest_user_id = member.id
              )
            ORDER BY member.id
            LIMIT batch_size
        ) INTO batch_ids;

        EXIT WHEN cardinality(batch_ids) = 0;
        last_user_id := batch_ids[cardinality(batch_ids)];

        -- Recheck eligibility on UPDATE so a concurrently selected image is preserved.
        UPDATE public.users AS member
        SET profile_image_code = (1 + get_byte(uuid_send(member.id), 15) % 5)::SMALLINT
        WHERE member.id = ANY(batch_ids)
          AND member.profile_image_code IS NULL
          AND member.deleted_at IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM public.guest_sessions AS guest
              WHERE guest.guest_user_id = member.id
          );
        GET DIAGNOSTICS affected_rows = ROW_COUNT;
        total_rows := total_rows + affected_rows;

        COMMIT;
        RAISE NOTICE 'Profile image backfill: committed %, total %, cursor %',
            affected_rows, total_rows, last_user_id;
    END LOOP;
END;
$$;
