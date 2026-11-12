# Data Validation

**Page:** Data & Analytics → **Data Validation** (`/data-validation`)

Scouting data is only as good as the entries behind it. A mistyped number, a scout watching the wrong robot or a skipped match quietly skews every average, prediction and pick list built on it. Data Validation checks your scouting against the official results so you can find and fix those entries, ideally while the scouts still remember the match.

![Data Validation](/assets/images/docs/data-validation.webp)

## What it checks

### Match scores: Match Score vs Alliance Scouted Sums

For every match at the event, each alliance's scouted points (the three robots' entries, scored with your match form's point values) are added up and compared with the official alliance score from The Blue Alliance or FIRST.

Each alliance gets one status:

| Status | Meaning |
|---|---|
| **Fully scouted** | All three robots have an entry |
| **Missing scouting for team(s) …** | Some robots were scouted, others weren't |
| **No teams scouted yet** | No entries for this alliance |
| **Score discrepancy** | Fully scouted, but the scouted sum is off from the official score by at least the threshold |

Scouted sums are rarely exact: fouls, ranking-point bonuses and anything your form doesn't track are in the official score but not in the scouting. That's why the threshold exists. A consistent gap across most matches usually means the form is missing something. A big gap in one match usually means a bad entry.

Open a match for its breakdown: each robot's scouted points, who scouted it, **View Entry** to open the entry, and **Scout Now** for robots that are missing.

### Team averages: Team Scouting Average vs EPA, xP & OPR

Each team's average scouted points per match are compared with whichever outside ratings your team has turned on (**Statbotics EPA**, **Match 13 xP**, **TBA OPR**, under Admin Settings → API keys → Optional stats). A team is flagged when its average differs from a rating by at least the threshold, or when alliance partners sent conflicting entries for it.

Ratings measure contribution in a different way than scouting does, so some difference is normal. Look for teams whose difference stands out from the rest.

## Controls

- **Choose Event:** the event to check.
- **Anomaly Threshold:** how many points of difference count as a problem: 5, 10, **15 (default)**, 20 or 30. Raise it if your form leaves out a lot of scoring; lower it to catch smaller errors.
- **Include Prescout:** also count prescout entries.
- **Filter Status:** show all matches, only fully scouted ones, only incomplete ones or warnings, or only anomalies.

The summary at the top counts fully scouted, incomplete and unscouted matches, and how many matches and teams have anomalies.

## Fixing what it finds

1. **Missing scouting:** press **Scout Now** to fill the gap, or check **Scout Assignments** to see who was assigned.
2. **Score discrepancy in one match:** open the robot entries with **View Entry** and compare them with the match video. Common causes are the wrong team or match number, or a value entered in the wrong field.
3. **A team that's off in every match:** check all of that team's entries on **Match Data** or the team's profile. One scout may be misreading that robot.
4. **Every match is off by a similar amount:** the form probably doesn't track some scoring. Check the point values in **Admin Settings → Scouting configs**.

Validation always uses plain averages, even when [Recency Bias](PREDICTIONS_AND_RANKINGS.md#recency-bias) is on, so recent matches don't hide old errors.

## For developers

`analytics/ValidationService.kt` (`validateEvent`) builds the report, served by `GET /api/validation?eventKey=…&threshold=…&forcePrescout=…`. Official scores are read from the stored match JSON in TBA, FIRST or FTC format.
