# TODO

## Stretch goal: search episode descriptions

Search currently matches titles only. Wanted: find episodes by words from their description too (e.g.
"bolivia" finds the Top Gear special, "topgear bolivia" narrows it to that show).

Plex's title filter can't search descriptions, so the idea is to load the shown libraries once when the
search screen opens (`/library/sections/{key}/all?type=4` gives every episode with its summary) and
match on the TV while typing: every word must appear, ignoring case and accents, also across spaces
("topgear" finds "Top Gear"), and a show's name alone shouldn't list all its episodes.

Decided (2026-10-31):

1. **Scope:** episodes only, by title and description. Movies, shows and other videos keep matching by
   title.
2. **Layout:** grouped by show, one row per show (e.g. Top Gear, The Grand Tour) with its matching
   episodes in season order, below the title matches.
3. **Matching:** every word must appear; ignores case and accents; "topgear" = "Top Gear"; the show's
   name can narrow ("topgear bolivia") but alone doesn't list all its episodes.
4. **Loading:** keep a copy of the episode list on the TV so Search is instant; refresh it quietly in
   the background each time Search opens.
5. **Cards:** the same episode cards as the show page (thumbnail, title, S · E), no description snippet.

A first, unbuilt version is parked on the local branch `stretch/description-search`. It differs from
the decisions in two ways: it reloads everything each time Search opens (no saved copy), and it lists
episodes per library rather than in one row per show.
