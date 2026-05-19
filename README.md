Obsidianscout

Overview
- Kotlin and Gradle server with a modern UI for FRC scouting
- Login uses username, team number, and password
- Supports SQLite and Postgres
- Scouting form and analytics are generated from JSON config

Quick start
1) Update config/app-config.json
2) Run ./gradlew run (or use gradle run if the wrapper is not available)
3) Open the app at localhost on port 8443 for HTTPS (port 8080 for HTTP if needed)

Configuration
- Server and database settings live in config/app-config.json
- The default scouting config lives in config/default-scouting-config.json
- The web UI can edit the scouting config and manage users

Login and users
- A default admin user is seeded on first run
- Change the admin password in config/app-config.json before sharing the server
- Use the admin page to create users for other teams

HTTPS
- The server auto-generates a local keystore at config/obsidianscout.jks if missing
- Update the keystore password in config/app-config.json for production use

SQLite
- Set database.type to sqlite
- Set database.sqlite.file to a local path

Postgres
- Set database.type to postgres
- Fill in database.postgres settings
