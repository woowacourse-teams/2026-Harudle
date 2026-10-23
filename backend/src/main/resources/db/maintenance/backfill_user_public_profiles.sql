CREATE OR REPLACE PROCEDURE pg_temp.backfill_user_public_profiles(batch_size INTEGER DEFAULT 100)
LANGUAGE plpgsql
AS $$
DECLARE
    batch_ids UUID[];
    last_user_id UUID;
    member RECORD;
    selected_nickname VARCHAR(30);
    batch_rows INTEGER;
    total_rows BIGINT := 0;
    remaining_rows BIGINT;
BEGIN
    IF batch_size IS NULL OR batch_size < 1 OR batch_size > 10000 THEN
        RAISE EXCEPTION 'batch_size must be between 1 and 10000' USING ERRCODE = '22023';
    END IF;

    LOOP
        SELECT ARRAY(
            SELECT u.id
            FROM public.users u
            WHERE u.id >= COALESCE(last_user_id, '00000000-0000-0000-0000-000000000000'::UUID)
              AND (last_user_id IS NULL OR u.id > last_user_id)
              AND (u.nickname IS NULL OR u.profile_image_code IS NULL)
              AND u.deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM public.guest_sessions g WHERE g.guest_user_id = u.id
              )
            ORDER BY u.id
            LIMIT batch_size
            FOR UPDATE OF u SKIP LOCKED
        ) INTO batch_ids;

        EXIT WHEN cardinality(batch_ids) = 0;
        last_user_id := batch_ids[cardinality(batch_ids)];
        batch_rows := 0;

        FOR member IN SELECT id, nickname FROM public.users WHERE id = ANY(batch_ids) ORDER BY id
        LOOP
            selected_nickname := member.nickname;

            IF member.nickname IS NULL THEN
                SELECT c.nickname INTO selected_nickname
                FROM public.nickname_candidates c
                WHERE c.assigned_user_id IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM public.users u WHERE u.nickname = c.nickname AND u.deleted_at IS NULL
                  )
                ORDER BY c.allocation_order
                LIMIT 1
                FOR UPDATE OF c SKIP LOCKED;

                IF selected_nickname IS NULL THEN
                    IF EXISTS (
                        SELECT 1 FROM public.nickname_candidates c
                        WHERE c.assigned_user_id IS NULL
                          AND NOT EXISTS (
                              SELECT 1 FROM public.users u
                              WHERE u.nickname = c.nickname AND u.deleted_at IS NULL
                          )
                    ) THEN
                        RAISE EXCEPTION 'Available nickname candidates are locked; retry later'
                            USING ERRCODE = '55P03';
                    END IF;
                    RAISE EXCEPTION 'Nickname candidate pool is exhausted; add candidates before retrying';
                END IF;
            END IF;

            UPDATE public.users
            SET nickname = selected_nickname,
                profile_image_code = COALESCE(profile_image_code, (floor(random() * 5) + 1)::SMALLINT),
                updated_at = clock_timestamp()
            WHERE id = member.id;

            IF member.nickname IS NULL THEN
                UPDATE public.nickname_candidates
                SET assigned_user_id = member.id
                WHERE nickname = selected_nickname;
            END IF;
            batch_rows := batch_rows + 1;
        END LOOP;

        COMMIT;
        total_rows := total_rows + batch_rows;
        RAISE NOTICE 'Public profile backfill: committed %, total %, cursor %',
            batch_rows, total_rows, last_user_id;
    END LOOP;

    SELECT count(*) INTO remaining_rows
    FROM public.users u
    WHERE (u.nickname IS NULL OR u.profile_image_code IS NULL)
      AND u.deleted_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM public.guest_sessions g WHERE g.guest_user_id = u.id);

    RAISE NOTICE 'Public profile backfill: processed %, remaining %', total_rows, remaining_rows;
    IF remaining_rows > 0 THEN
        RAISE EXCEPTION 'Unprocessed members remain; retry after concurrent transactions finish'
            USING ERRCODE = '55P03';
    END IF;
END;
$$;
