document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) {
        return;
    }

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    const isUserSuperAdmin = me.role === "SUPERADMIN";
    if (!isUserSuperAdmin) {
        document.getElementById("superadmin-locked").classList.remove("hidden");
        document.getElementById("migration-panel").classList.add("hidden");
        return;
    }

    const sourceTypeSelect = document.getElementById("source-type");
    const sqliteFields = document.getElementById("sqlite-fields");
    const postgresFields = document.getElementById("postgres-fields");
    const btnStart = document.getElementById("btn-start-migration");

    sourceTypeSelect.addEventListener("change", () => {
        if (sourceTypeSelect.value === "sqlite") {
            sqliteFields.classList.remove("hidden");
            postgresFields.classList.add("hidden");
        } else {
            sqliteFields.classList.add("hidden");
            postgresFields.classList.remove("hidden");
        }
    });

    let pollInterval = null;

    function startStatusPolling() {
        document.getElementById("migration-idle").classList.add("hidden");
        document.getElementById("migration-active").classList.remove("hidden");
        btnStart.disabled = true;

        if (pollInterval) clearInterval(pollInterval);

        const statusKeys = {
            "Migrating users...": "migration.status.users",
            "Migrating events...": "migration.status.events",
            "Migrating teams...": "migration.status.teams",
            "Migrating matches...": "migration.status.matches",
            "Migrating scouting data...": "migration.status.scouting",
            "Migrating alliances...": "migration.status.alliances",
            "Migration completed successfully!": "migration.status.success"
        };
        const getLocalizedStatus = (msg) => {
            if (!msg) return "";
            const key = statusKeys[msg];
            return key ? Obsidianscout.t(key, msg) : Obsidianscout.localize(msg);
        };

        pollInterval = setInterval(async () => {
            try {
                const status = await Obsidianscout.request("/api/admin/migration/status");

                // Update progress bar & text
                document.getElementById("migration-progress-bar").style.width = status.progress + "%";
                document.getElementById("migration-progress-percentage").textContent = status.progress + "%";
                document.getElementById("migration-status-text").textContent = getLocalizedStatus(status.message);

                // Update stats
                document.getElementById("count-users").textContent = status.usersMigrated;
                document.getElementById("count-events").textContent = status.eventsMigrated;
                document.getElementById("count-teams").textContent = status.teamsMigrated;
                document.getElementById("count-matches").textContent = status.matchesMigrated;
                document.getElementById("count-scouting").textContent = status.scoutingDataMigrated;
                document.getElementById("count-alliances").textContent = status.alliancesMigrated;

                if (!status.running) {
                    clearInterval(pollInterval);
                    btnStart.disabled = false;
                    if (status.success) {
                        alert(Obsidianscout.t("migration.status.success", "Migration completed successfully!"));
                    } else {
                        alert(Obsidianscout.t("migration.failed", "Migration failed: ") + getLocalizedStatus(status.message));
                    }
                }
            } catch (e) {
                console.error("Polling status failed:", e);
            }
        }, 1000);
    }

    btnStart.addEventListener("click", async () => {
        const type = sourceTypeSelect.value;
        let payload = {
            sourceType: type
        };

        if (type === "sqlite") {
            const path = document.getElementById("sqlite-path").value.trim();
            if (!path) {
                alert(Obsidianscout.t("migration.sqlite_path_required", "Please enter the folder path"));
                return;
            }
            payload.sqliteInstancePath = path;
        } else {
            const host = document.getElementById("pg-host").value.trim();
            const port = parseInt(document.getElementById("pg-port").value.trim()) || 5432;
            const user = document.getElementById("pg-user").value.trim();
            const passwordPlain = document.getElementById("pg-password").value;
            const database = document.getElementById("pg-db-main").value.trim();
            const databaseUsers = document.getElementById("pg-db-users").value.trim();
            const databasePages = document.getElementById("pg-db-pages").value.trim();
            const databaseMisc = document.getElementById("pg-db-misc").value.trim();
            const databaseImages = document.getElementById("pg-db-images").value.trim();
            const databaseStatboticsepa = document.getElementById("pg-db-statboticsepa").value.trim();

            if (!host || !user || !database) {
                alert(Obsidianscout.t("migration.pg_fields_required", "Host, Username, and Main Database are required."));
                return;
            }

            payload.pgConfig = {
                host,
                port,
                user,
                passwordPlain,
                database,
                databaseUsers,
                databasePages,
                databaseMisc,
                databaseImages,
                databaseStatboticsepa
            };
        }

        btnStart.disabled = true;
        try {
            const res = await Obsidianscout.request("/api/admin/migration/run", {
                method: "POST",
                json: payload
            });
            if (res.success) {
                startStatusPolling();
            } else {
                alert(Obsidianscout.t("migration.failed_start", "Failed to start migration: ") + (res.error || "Unknown error"));
                btnStart.disabled = false;
            }
        } catch (e) {
            alert("Error: " + e.message);
            btnStart.disabled = false;
        }
    });

    // Reset Database logic
    const btnResetDatabase = document.getElementById("btn-reset-database");
    const resetPasswordConfirm = document.getElementById("reset-password-confirm");

    if (btnResetDatabase && resetPasswordConfirm) {
        btnResetDatabase.addEventListener("click", async () => {
            const password = resetPasswordConfirm.value;
            if (!password) {
                alert("Please enter your password to confirm database reset.");
                return;
            }

            if (!confirm("ARE YOU ABSOLUTELY SURE? This will permanently delete all data in the database!")) {
                return;
            }

            btnResetDatabase.disabled = true;
            try {
                const res = await Obsidianscout.request("/api/admin/reset-database", {
                    method: "POST",
                    json: { password: password }
                });

                if (res.success) {
                    alert("Database reset completed successfully!");
                    window.location.reload();
                } else {
                    alert("Failed to reset database: " + (res.error || "Unknown error"));
                    btnResetDatabase.disabled = false;
                }
            } catch (e) {
                alert("Error resetting database: " + e.message);
                btnResetDatabase.disabled = false;
            }
        });
    }

    // Check initial status
    try {
        const initialStatus = await Obsidianscout.request("/api/admin/migration/status");
        const statusKeys = {
            "Migrating users...": "migration.status.users",
            "Migrating events...": "migration.status.events",
            "Migrating teams...": "migration.status.teams",
            "Migrating matches...": "migration.status.matches",
            "Migrating scouting data...": "migration.status.scouting",
            "Migrating alliances...": "migration.status.alliances",
            "Migration completed successfully!": "migration.status.success"
        };
        const getLocalizedStatus = (msg) => {
            if (!msg) return "";
            const key = statusKeys[msg];
            return key ? Obsidianscout.t(key, msg) : Obsidianscout.localize(msg);
        };

        if (initialStatus.running) {
            startStatusPolling();
        } else if (initialStatus.progress > 0) {
            // Show last run stats
            document.getElementById("migration-idle").classList.add("hidden");
            document.getElementById("migration-active").classList.remove("hidden");
            document.getElementById("migration-progress-bar").style.width = initialStatus.progress + "%";
            document.getElementById("migration-progress-percentage").textContent = initialStatus.progress + "%";
            document.getElementById("migration-status-text").textContent = getLocalizedStatus(initialStatus.message);
            document.getElementById("count-users").textContent = initialStatus.usersMigrated;
            document.getElementById("count-events").textContent = initialStatus.eventsMigrated;
            document.getElementById("count-teams").textContent = initialStatus.teamsMigrated;
            document.getElementById("count-matches").textContent = initialStatus.matchesMigrated;
            document.getElementById("count-scouting").textContent = initialStatus.scoutingDataMigrated;
            document.getElementById("count-alliances").textContent = initialStatus.alliancesMigrated;
        }
    } catch (e) {
        console.error("Failed to fetch initial migration status:", e);
    }
});

