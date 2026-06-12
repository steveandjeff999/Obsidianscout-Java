# ObsidianScout Java Ktor Mobile API Server Documentation

This document describes the mobile-specific endpoints implemented under `/api/mobile/*` in the Ktor Ktor server. These endpoints mirror the behavior of the python-based mobile API docs.

## 1. Authentication Scheme (JWT)

All protected routes require a stateless JSON Web Token (JWT) passed in the `Authorization` header:

```http
Authorization: Bearer <your_jwt_token>
```

Tokens are signed using `HS256` (HMAC with SHA-256) using the server's session secret (`appConfig.server.sessionSecret`).

### JWT Payload Claims:
*   `userId`: (Integer) Database ID of the user.
*   `username`: (String) Username of the user.
*   `teamNumber`: (Integer) Configured team number.
*   `role`: (String) User role (e.g., `ADMIN`, `SCOUTER`, `VIEWER`).
*   `email`: (String?) Optional email.
*   `exp`: (NumericDate) Expiration timestamp.

---

## 2. Authentication & Profile Endpoints

### Health Check
*   **Route**: `GET /api/mobile/health`
*   **Authentication**: None
*   **Response**:
    ```json
    {
      "success": true,
      "status": "healthy",
      "version": "1.0",
      "timestamp": "2026-06-12T16:00:00Z"
    }
    ```

### Login
*   **Route**: `POST /api/mobile/auth/login`
*   **Authentication**: None
*   **Request Body**:
    ```json
    {
      "username": "scouter1",
      "password": "securepassword",
      "team_number": 862
    }
    ```
*   **Response**:
    ```json
    {
      "success": true,
      "token": "eyJhbGciOiJIUzI1Ni...",
      "user": {
        "id": 1,
        "username": "scouter1",
        "team_number": 862,
        "roles": ["SCOUTER"],
        "profile_picture": "default.png",
        "is_active": true
      },
      "expires_at": "2026-06-13T16:00:00Z"
    }
    ```

### Register
*   **Route**: `POST /api/mobile/auth/register`
*   **Authentication**: None
*   **Request Body**:
    ```json
    {
      "username": "scouter1",
      "password": "securepassword",
      "confirm_password": "securepassword",
      "team_number": 862,
      "email": "scouter1@example.com"
    }
    ```

### Verify Token
*   **Route**: `GET /api/mobile/auth/verify`
*   **Authentication**: Protected (Requires Token)
*   **Response**:
    ```json
    {
      "success": true,
      "valid": true,
      "user": {
        "id": 1,
        "username": "scouter1",
        "team_number": 862,
        "roles": ["SCOUTER"],
        "profile_picture": "default.png",
        "is_active": true
      }
    }
    ```

### Refresh Token
*   **Route**: `POST /api/mobile/auth/refresh`
*   **Authentication**: Protected (Requires Token)
*   **Response**:
    ```json
    {
      "success": true,
      "token": "new_jwt_token_here",
      "expires_at": "2026-06-14T16:00:00Z"
    }
    ```

### Profile Details
*   **Route**: `GET /api/mobile/profile/me`
*   **Authentication**: Protected (Requires Token)
*   **Response**:
    ```json
    {
      "success": true,
      "user": {
        "id": 1,
        "username": "scouter1",
        "team_number": 862,
        "profile_picture": "default.png",
        "profile_picture_url": "/api/mobile/profile/picture?username=scouter1"
      }
    }
    ```

### Profile Picture
*   **Route**: `GET /api/mobile/profile/picture`
*   **Query Parameters**: `username` (optional)
*   **Authentication**: Protected (Requires Token)
*   **Response**: Serves profile picture image file (PNG/JPG). Falls back to `default.png`.

---

## 3. Core Resource Endpoints

### Events
*   **Route**: `GET /api/mobile/events`
*   **Authentication**: Protected (Requires Token)
*   **Response**:
    ```json
    {
      "success": true,
      "events": [
        {
          "id": 1,
          "name": "Midwest Regional",
          "code": "ilch",
          "location": "Chicago, IL",
          "start_date": "2026-03-05",
          "end_date": "2026-03-08",
          "timezone": "America/Chicago",
          "year": 2026,
          "team_count": 45
        }
      ]
    }
    ```

### Teams Listing (By Event Code)
*   **Route**: `GET /api/mobile/teams`
*   **Query Parameters**: `event_code` (e.g. `ilch`)
*   **Authentication**: Protected (Requires Token)
*   **Response**:
    ```json
    {
      "success": true,
      "teams": [
        {
          "id": 10,
          "team_number": 111,
          "team_name": "WildStang",
          "location": "Arlington Heights, IL"
        }
      ],
      "count": 1,
      "total": 1,
      "event": {
        "id": 1,
        "name": "Midwest Regional",
        "code": "ilch"
      }
    }
    ```

### Teams Listing (For Team's Current Active Event)
*   **Route**: `GET /api/mobile/teams/current`
*   **Authentication**: Protected (Requires Token)
*   **Response**: (Same structure as above)

### Team Details
*   **Route**: `GET /api/mobile/teams/{id}`
*   **Authentication**: Protected (Requires Token)
*   **Response**:
    ```json
    {
      "success": true,
      "team": {
        "id": 10,
        "team_number": 111,
        "team_name": "WildStang",
        "location": "Arlington Heights, IL"
      }
    }
    ```

### Matches Listing (By Event Code)
*   **Route**: `GET /api/mobile/matches`
*   **Query Parameters**: `event_code`, `match_type` (optional), `team_number` (optional)
*   **Authentication**: Protected (Requires Token)
*   **Response**:
    ```json
    {
      "success": true,
      "matches": [
        {
          "id": 123,
          "match_number": 1,
          "match_type": "qualification",
          "red_alliance": "111, 862, 2451",
          "blue_alliance": "2056, 254, 1678",
          "red_score": 120,
          "blue_score": 110,
          "winner": "red",
          "scheduled_time": "2026-06-12T10:00:00Z",
          "predicted_time": "2026-06-12T10:00:00Z",
          "actual_time": "2026-06-12T10:05:00Z"
        }
      ],
      "count": 1,
      "event": {
        "id": 1,
        "name": "Midwest Regional",
        "code": "ilch"
      }
    }
    ```

### Matches Listing (For Current Active Event)
*   **Route**: `GET /api/mobile/matches/current`
*   **Query Parameters**: `match_type` (optional), `team_number` (optional)
*   **Authentication**: Protected (Requires Token)
*   **Response**: (Same structure as above)

---

## 4. Scouting & Configurations

### Match Scouting Submit
*   **Route**: `POST /api/mobile/scouting/submit`
*   **Authentication**: Protected (Requires Token)
*   **Request Body**:
    ```json
    {
      "team_id": 10,
      "match_id": 123,
      "data": { "auto_amp": 2, "tele_speaker": 5, "climbed": true },
      "offline_id": "uuid-local-identifier",
      "qualitative": false
    }
    ```
*   **Response**:
    ```json
    {
      "success": true,
      "scouting_id": 45,
      "message": "Scouting entry submitted successfully",
      "offline_id": "uuid-local-identifier"
    }
    ```

### Bulk Scouting Submit
*   **Route**: `POST /api/mobile/scouting/bulk-submit`
*   **Authentication**: Protected (Requires Token)
*   **Request Body**:
    ```json
    {
      "entries": [
        {
          "team_id": 10,
          "match_id": 123,
          "data": { "auto_amp": 2, "tele_speaker": 5 },
          "offline_id": "uuid-1"
        }
      ]
    }
    ```
*   **Response**:
    ```json
    {
      "success": true,
      "submitted": 1,
      "failed": 0,
      "results": [
        {
          "offline_id": "uuid-1",
          "success": true,
          "scouting_id": 46
        }
      ]
    }
    ```

### Pit Scouting Submit
*   **Route**: `POST /api/mobile/pit-scouting/submit`
*   **Authentication**: Protected (Requires Token)
*   **Request Body**:
    ```json
    {
      "team_id": 10,
      "data": { "drive_type": "Swerve", "weight_lbs": 120 },
      "local_id": "unique-pit-local-id-for-duplicate-check",
      "images": ["base64_or_image_uri"]
    }
    ```
*   **Response**:
    ```json
    {
      "success": true,
      "pit_scouting_id": 23,
      "message": "Pit scouting entry submitted successfully"
    }
    ```
    *(Note: Re-submitting the same `local_id` returns the existing entry's ID rather than creating a duplicate).*

### Configurations Retrieval
*   **Active Scouting Config**: `GET /api/mobile/config/game` (also `/api/mobile/config/game/active`, `/api/mobile/config/game/team`)
*   **Active Pit Config**: `GET /api/mobile/config/pit` (also `/api/mobile/config/pit/active`, `/api/mobile/config/pit/team`)
*   **Active Qualitative Config**: `GET /api/mobile/config/qualitative` (also `/api/mobile/config/qualitative/active`, `/api/mobile/config/qualitative/team`)
*   **Authentication**: Protected (Requires Token)
*   **Response**: Config schema with fields.

### Configurations Save (Admin Only)
*   **Save Scouting Config**: `POST` or `PUT` to `/api/mobile/config/game`, `/api/mobile/config/game/active`, `/api/mobile/config/game/team`
*   **Save Pit Config**: `POST` or `PUT` to `/api/mobile/config/pit`, `/api/mobile/config/pit/active`, `/api/mobile/config/pit/team`
*   **Save Qualitative Config**: `POST` or `PUT` to `/api/mobile/config/qualitative`, `/api/mobile/config/qualitative/active`, `/api/mobile/config/qualitative/team`
*   **Authentication**: Protected + Admin Required
*   **Request Body**: Raw JSON string representing the new configuration schema.

### Data Mode Details
*   **Route**: `GET /api/mobile/config/data-mode`
*   **Authentication**: Protected (Requires Token)
*   **Response**:
    ```json
    {
      "success": true,
      "epa_source": "statbotics",
      "data_mode": "scouted"
    }
    ```

### Custom Match Points (EPA & Scouting Merged Metrics)
*   **Route**: `POST /api/mobile/config/current-data-mode-match-points`
*   **Authentication**: Protected (Requires Token)
*   **Request Body**:
    ```json
    {
      "event_code": "ilch",
      "team_numbers": [111, 862]
    }
    ```
*   **Response**: Full breakdown of team match counts, EPA, auto/teleop/endgame points derived from scouted and/or external API data.

### Historical EPA/OPR
*   **Route**: `POST /api/mobile/config/epa-opr-history`
*   **Authentication**: Protected (Requires Token)
*   **Request Body**:
    ```json
    {
      "event_code": "ilch",
      "team_numbers": [111]
    }
    ```
*   **Response**: List of team match EPA/OPR history.

---

## 5. Collaborative Alliances

*   **List Alliances & Invites**: `GET /api/mobile/alliances`
*   **Create Alliance**: `POST /api/mobile/alliances`
    *   *Body*: `{ "name": "Alliance Name", "description": "Notes" }`
*   **Invite Partner Team**: `POST /api/mobile/alliances/invite`
    *   *Body*: `{ "team_number": 112 }`
*   **Respond to Pending Invite**: `POST /api/mobile/alliances/respond`
    *   *Body*: `{ "response": "accept" }` or `{ "response": "decline" }`
*   **Leave Alliance**: `POST /api/mobile/alliances/leave`
*   **Toggle/Activate Alliance**: `POST /api/mobile/alliances/toggle`
    *   *Body*: `{ "activate": true }`

---

## 6. Chat Messaging & Group System

Chat history and user states are backed by isolated file storage under `instance/chat/` grouped by team.

*   **Retrieve Chat Messages**: `GET /api/mobile/chat/messages`
    *   *Query Parameters*: `recipient_id` (User DB ID for DM), `group` (Group name), `alliance` (Boolean)
*   **List Conversation Members**: `GET /api/mobile/chat/members`
*   **Send Chat Message**: `POST /api/mobile/chat/send`
    *   *Body*: `{ "recipient_id": 2, "body": "Hello!", "offline_id": "msg-local-id" }`
*   **List User's Conversations**: `GET /api/mobile/chat/conversations`
*   **Update Read receipts**: `POST /api/mobile/chat/read`
*   **Edit Message**: `POST /api/mobile/chat/edit`
*   **Delete Message**: `POST /api/mobile/chat/delete`
*   **React with Emoji**: `POST /api/mobile/chat/react`
*   **Get Chat State**: `GET /api/mobile/chat/state`
*   **Manage Groups**:
    *   `GET /api/mobile/chat/groups` -> List groups
    *   `POST /api/mobile/chat/groups` -> Create new group
    *   `GET /api/mobile/chat/groups/members` -> List group members
    *   `POST /api/mobile/chat/groups/members` -> Manage/replace group members

---

## 7. Sync controls & Stubs

*   **Retrieves Sync status**: `GET /api/mobile/sync/status`
*   **Triggers sync**: `POST /api/mobile/sync/trigger`
*   **Visualize Chart Data**: `GET` or `POST` `/api/mobile/graphs/visualize` (returns fallback Plotly chart schema).
*   **Notification stubs**: `GET /api/mobile/notifications/scheduled`, `/api/mobile/notifications/past`, and `/api/mobile/notifications/unread` (returns empty lists `[]`).

---

## 8. Admin User Management

These endpoints require admin or superadmin privileges. Team-scoped admins may only manage users within their own team.

*   **List Roles**: `GET /api/mobile/admin/roles`
*   **List Users**: `GET /api/mobile/admin/users`
    *   *Query Parameters*: `search` (optional search filter), `include_inactive` (optional boolean flag)
*   **Create User**: `POST /api/mobile/admin/users`
    *   *Body*: `{ "username": "new_user", "password": "password", "email": "user@example.com", "team_number": 862, "roles": ["scout"] }`
*   **Get User Details**: `GET /api/mobile/admin/users/{user_id}`
*   **Edit User Details**: `PUT /api/mobile/admin/users/{user_id}`
    *   *Body (all optional)*: `{ "username": "new_username", "password": "newpassword", "email": "new_email@example.com", "team_number": 862, "roles": ["scout"] }`
*   **Delete User**: `DELETE /api/mobile/admin/users/{user_id}`

---

## 9. Status Codes & Error Formats

In case of a mobile API failure, a standardized error payload is returned with the appropriate HTTP status:

```json
{
  "success": false,
  "error": "Authentication token is missing",
  "error_code": "AUTH_REQUIRED"
}
```

### Common `error_code` Values:
*   `AUTH_REQUIRED`: The request lacks an `Authorization` header.
*   `INVALID_TOKEN`: The JWT signature is invalid or the token has expired.
*   `FORBIDDEN`: The user does not have permission to access the resource (e.g. Admin endpoint).
*   `INVALID_CREDENTIALS`: Password or team/username mismatch during login.
*   `MISSING_BODY`: Request body is malformed or missing.
*   `DUPLICATE_ENTRY`: Local duplicate check caught an existing submission.
