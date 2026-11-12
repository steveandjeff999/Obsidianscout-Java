# Custom Analytics

**Page:** Data & Analytics → **Custom Analytics** (`/custom-analytics`)

Custom Analytics is a dashboard builder for your scouting data. Where **Analytics** shows a fixed set of widgets from your scouting config, here you choose the charts, the metrics and the filters, then save the dashboard for your team or share it with a link.

![Custom Analytics](/assets/images/docs/custom-analytics.webp)

## Start from a template

Press **Templates** for a ready-made dashboard you can then change:

| Template | What you get |
|---|---|
| **Blank Dashboard** | An empty canvas |
| **Match Strategy & Offense** | Auto vs teleop points, stacked phase contributions, consistency box plots and a ranking matrix |
| **Pick List & Capability** | Team radar profiles, an EPA correlation scatter, driver ratings and a scoring breakdown |

## Add a visual

Press **Add Visual** and set:

- **Visual Type:** Bar / Column, Stacked Bar, Line / Trend, Scatter, Box Plot, Violin, Radar, Donut, KPI Card, Matrix, Heatmap or Histogram.
- **Axis / Dimension (X):** what each bar or point stands for: team number, match number or event.
- **Primary Measure (Y):** any field from your scouting forms, any calculated metric, and the outside ratings your team uses.
- **Aggregation Function:** how each team's (or match's) entries are combined: average, sum, median, max, min, count of entries, standard deviation or 75th percentile.
- **Secondary Measures / Stacks:** extra measures for stacked bars, multi-line trends or radar axes.
- **Sort Order**, **Top N Items** (0 shows all), an optional **Benchmark Target Line**, the **Card Width Span** (a quarter to the full width) and a **Color Palette**.

**Click a team** in any chart to cross-filter: every visual highlights that team until you press **Clear Filter**.

## Filters

The **Filters** bar applies to every visual on the dashboard:

- **Event:** which event's data to use.
- **Match scope:** Practice, Quals and/or Playoffs, and **Include Prescout**.
- **Teams:** all teams, the **Top 8**, or a hand-picked set.

## Calculated metrics

Press **New Metric** to define a new measure from existing fields. Write the formula with field IDs in square brackets; **Insert Available Fields** adds them for you:

```
[auto_coral] * 3 + [teleop_coral] * 2 + [endgame_points]
```

Give it a display name and an alphanumeric ID, and it becomes available as a measure in every visual on that dashboard. Formulas are ordinary arithmetic (`+ - * /` and parentheses). Entries missing a field count it as 0.

## Save, share and present

- **Save:** stores the dashboard with a title, a category (Strategy, Pick List, Driver Analysis, Scoring Breakdown, Alliance Partner or General) and a description. Turn on **Share with Team** to make it visible to your teammates, and **Set as My Default** to open it automatically next time.
- **Reports:** your team's saved dashboards. You can also **Export Config JSON** to back a dashboard up or move it to another team, and **Import Config JSON** to load one.
- **Share:** creates a link that opens the dashboard read-only without signing in. Choose when it expires, protect it with a PIN if you like, and show its QR code for people to scan. Manage and revoke links on **Shared Links**.
- **Export:** download the dashboard's data as CSV, or the report definition as JSON.
- **Kiosk:** full-screen presentation mode that refreshes the data every 45 seconds. Good for a pit display.

## Tips

- Use **Box Plot** or **Violin** with the standard deviation in mind to tell consistent teams from streaky ones; two teams with the same average can be very different picks.
- A **Scatter** of your scouted points against EPA shows which teams your scouts rate higher or lower than the outside ratings. Big outliers are worth a look on [Data Validation](DATA_VALIDATION.md).
- Saved team dashboards are a good place to agree on the metrics your pick list meeting will use.
