-- Remove only the retired private friendship fixtures, never real Keycloak
-- subjects or the separate public-content seed (seed:*). Chat FKs do not
-- cascade, so remove their children explicitly before deleting fixture users.
DELETE FROM chat_messages
WHERE sender_id IN (SELECT id FROM users WHERE keycloak_id LIKE 'friend-seed:%');

DELETE FROM conversation_participants
WHERE user_id IN (SELECT id FROM users WHERE keycloak_id LIKE 'friend-seed:%');

-- Friend requests, friendships and owned channels cascade. Channel children
-- (publications/comments/likes/follows) cascade through their existing FKs.
DELETE FROM users WHERE keycloak_id LIKE 'friend-seed:%';
