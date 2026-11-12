# Predictions and Rankings

ObsidianScout predicts matches and final rankings from two kinds of data:

- **Your scouting:** each team's average scouted points per match, scored with the point values in your match scouting form.
- **Outside ratings** (optional): **Statbotics EPA**, **Match 13 xP** and **TBA OPR** (FTC Scout OPR for FTC). Turn each one on under **Admin Settings → API keys → Optional stats**. Ratings that are off are hidden everywhere.

This page covers the [Match Predictor](#match-predictor), the [Event Predictor](#event-predictor), [Projected Rankings](#projected-rankings), the [Match predictions game](#match-predictions-game), and the [Recency Bias](#recency-bias) setting that affects all of them.

---

## Match Predictor

**Page:** Strategy → **Predictor** (`/predictor`)

Pick a match from the schedule to see both alliances side by side. Each source gets its own winner card:

- **Scouted:** the sum of the three teams' average scouted points. The card shows the predicted margin in points.
- **EPA / OPR:** the sum of the three teams' ratings.
- **xP:** Match 13's own prediction for that match when it has one, otherwise the sum of the teams' xP.

Use **Data Source** to show one source or **All 3** at once. The team rows show each team's scouted average, how many matches it was scouted in, and its ratings. A team with conflicting entries from alliance partners is flagged, so you know its average may be off.

The sources often disagree early in an event. Scouted data reflects what your scouts saw at this event. EPA and OPR are built from official scores and carry over from earlier events.

![Match Predictor](/assets/images/docs/predictor.webp)

## Event Predictor

**Page:** Strategy → **Event Predictor** (`/event-predictor`)

The same prediction for every match at an event in one list. Choose the event and the **Prediction Model** (Scouted Data, Statbotics EPA, Match 13 xP, TBA OPR or all three). Open a match for the full alliance comparison. **Force Prescout Data** builds the scouted predictions from prescout entries, which is useful before the event when you have no event data yet.

![Event Predictor](/assets/images/docs/event-predictor.webp)

## Projected Rankings

**Page:** Data & Analytics → **Projected Rankings** (`/projected-rankings`)

Estimates where each team will finish quals by simulating the rest of the qualification schedule thousands of times (2,000 by default; choose 1,000 to 10,000 runs).

How it works:

1. **Played matches count as they happened.** When The Blue Alliance publishes each alliance's ranking points, those are used. Otherwise RP is estimated from wins and losses.
2. **Each team gets a strength.** It's a weighted blend of the team's scouted average (weight 1) with the outside ratings that are turned on: EPA and xP (0.8 each) and OPR (0.6). This is the same blend as the **Weighted** metric on Alliance Selection. Teams with no data get the event median.
3. **Unplayed matches are simulated** from those strengths, scaled and given random variation calibrated against the scores already played at this event.
4. **Teams are ranked** by average RP per match, with average match score as the tiebreaker.

The table shows each team's current rank and record, its projected rank, its **Likely Range** (10th to 90th percentile finish), and its chance of finishing first or in the alliance-captain slots (top 8 in FRC, top 4 in FTC). **Re-run** draws a new set of simulations. Once every qualification match has been played, the table shows the final standings instead of a projection.

Results are cached for five minutes as long as nothing changes, so reopening the page is quick.

![Projected Rankings](/assets/images/docs/projected-rankings.webp)

## Match predictions game

**Pages:** Scouting → **Match Predictions** (`/match-predictions`) and **Prediction Leaderboard** (`/prediction-leaderboard`)

A friendly competition for your scouts. Before a match, each scout calls the winner (red or blue) on the Match Predictions page or straight from the match and qualitative scouting forms. Picks lock when the match result syncs (practice matches lock once they're assumed played).

The leaderboard ranks your team's scouts by accuracy for **one event** or the **whole season**, and shows correct picks, ties, pending picks, the current streak and the best streak. Ties don't count for or against anyone.

The game is **off by default**. A team admin turns it on with **Enable Match Predictions** under **Admin Settings → Permissions → General Permissions**. While it's off, both pages show "Match predictions are off" instead.

![Match Predictions](/assets/images/docs/match-predictions.webp)

## Recency Bias

**Setting:** Admin Settings → API keys → **Recency Bias**

Robots change during an event: they get fixed, break, or learn a new auto. Recency bias makes a team's scouted average count its recent matches more than its early ones. When it's on, it applies to predictions, team averages, rankings and Compare Teams. Data Validation always uses plain averages, so it compares like with like.

| Option | Default | What it does |
|---|---|---|
| **Weight recent matches more** | Off | Turns recency bias on |
| **Mode** | Exponential decay | **Exponential decay** fades older matches gradually. **Last N matches only** ignores everything except the most recent N |
| **Half-life (matches)** | 4 | Exponential mode: how many matches back a match counts half as much. Smaller numbers react faster but are noisier |
| **Matches to count (N)** | 6 | Last N mode: how many recent matches count |
| **Minimum matches before weighting** | 4 | Teams with fewer scouted matches keep a plain average, so one or two matches don't dominate early on |
| **Prescout weight (0–1)** | 0.5 | How much prescout entries count when they're mixed in (0 ignores them, 1 counts them like event matches). Prescout always counts as older than event matches |

Several scouts' entries for the same match count as one match, so duplicate scouting from alliance partners doesn't tip the weights.

## For developers

- Match predictions: `analytics/PredictorService.kt` (`predict`, `predictAll`). `predictAll` results are cached per data version, so any write retires them.
- Projected rankings: `analytics/RankingProjectionService.kt`.
- Recency weights: `analytics/RecencyWeighting.kt`, with settings in `RecencyBiasSettings` (`integrations/SettingsService.kt`).
- The predictions game: `scouting/MatchPredictionService.kt`.
