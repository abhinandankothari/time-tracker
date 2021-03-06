-- name: retrieve-user-data-query
-- Retrieves user data.
SELECT * FROM app_user
WHERE app_user.google_id = :google_id
LIMIT 1;

-- name: retrieve-all-users-query
-- Retrieve all the users.
SELECT * FROM app_user;

-- name: create-user-query<!
-- Inserts a user in the database.
INSERT INTO app_user
(google_id, name, email)
VALUES (:google_id, :name, :email)
ON CONFLICT DO NOTHING;

-- name: has-role-query
-- Checks if a user has a particular role.
SELECT COUNT(*) FROM app_user
WHERE google_id = :google_id
AND role = :role::user_role
