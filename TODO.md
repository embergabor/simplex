# TODO

## Stretch goal: search episode descriptions

Search currently matches titles only. Wanted: find episodes by words from their description too (e.g.
"bolivia" finds the Top Gear special, "topgear bolivia" narrows it to that show).

Plex's title filter can't search descriptions, so the idea is to load the shown libraries once when the
search screen opens (`/library/sections/{key}/all?type=4` gives every episode with its summary) and
match on the TV while typing: every word must appear, ignoring case and accents, also across spaces
("topgear" finds "Top Gear"), and a show's name alone shouldn't list all its episodes.

A first, unbuilt version is parked on the local branch `stretch/description-search`.
