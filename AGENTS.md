# orthophoto-king

Application to align drone images onto a map and cut them into map tiles.

This file is the **ground truth and session state** for the project. Consult it
before answering any question or making any change, and update it whenever a new
finding or decision is made (see "Decisions log" and "Open questions" below).

## Public repository

**orthophoto-king is a public repo** (github.com/vanjakom/orthophoto-king).
Personal data must not go into any committed file, including this one and code
comments. That covers:

- GPS coordinates or place names from the user's images;
- real image paths or file names;
- store contents;
- private dataset locations or tile layers.

Use placeholders in examples instead (`DJI_0001.JPG`, `<lat>, <lon>`,
`<tile-base>`). Session findings about real data go in chat, not here.

## Purpose

A user loads a drone image, which is drawn on top of a base map. The user
centers and fits it by adjusting longitude, latitude, span and rotation. As much
of the initial state as possible comes from the image's EXIF. Once the user is
happy, they submit the parameters to the server, which stores them. On user
request, a `clj-scheduler` job cuts map tiles from the image using the stored
parameters.

The tool aligns **one image at a time**.

## Stack

- **Build:** Leiningen (`project.clj`). Our own libraries are symlinked into
  `checkouts/`, the same way as in `~/projects/uberjvm`. `checkouts/` is
  gitignored. Currently: `clj-common`.
- **Server:** Clojure. HTTP server and helpers come from
  [clj-common](https://github.com/vanjakom/clj-common) (see "Libraries").
- **Client:** HTML + JavaScript, a single page.
- **Map:** Leaflet, with the
  [Leaflet.ImageOverlay.Rotated](https://github.com/IvanSanchez/Leaflet.ImageOverlay.Rotated)
  plugin to draw the rotated drone image.
- **Background processing:** a tile job written against `clj-common.context`
  only. It is clj-scheduler compatible, but **clj-scheduler is not a
  dependency**. The job is started on user request.
- **HTTP port:** 6080.
- **Storage:** a single EDN file, `~/dataset-git/orthophoto/store.edn`. The
  image, the store and the tiles are all on the local fs.
- **Paths:** clj-common path vectors, e.g. `["Users" "vanja" "projects"]`.
  Paths are stored in this form, never as strings.

### Base layers

| Name             | URL template                                                  |
|------------------|---------------------------------------------------------------|
| OSM              | `https://tile.openstreetmap.org/{z}/{x}/{y}.png`              |
| Google Satellite | `http://mt0.google.com/vt/lyrs=s&hl=en&x={x}&y={y}&z={z}`     |
| custom           | to be defined                                                 |

Bing has been dropped for now.

## Routes

| Route                 | Purpose                                              |
|-----------------------|------------------------------------------------------|
| `/tool/align`         | The alignment tool, a single web page                 |
| `/tool/generate-tile` | Starts the tile-generation job                        |
| `/data/orthophoto`    | Serves the current image                              |
| `/data/retrieve`      | Serves the current image parameters                   |
| `/data/update`        | Updates (stores) the parameters                       |
| `/data/list`          | JSON list of all images (see below)                   |
| `/data/add/*`         | Adds the image at `*`, with initial parameters        |

The store holds multiple images, and the align tool works on one at a time.
**The image id is the image path.** Routes that take an image put its path in
the rest of the URL (compojure `*`, read from `[:params :*]` as in
clj-scheduler and notes-viewer). For example,
`/data/add/Users/vanja/dataset-git/orthophoto/DJI_0001.JPG`. The same pattern
is used by `/tool/align/*`, `/data/orthophoto/*`, `/data/retrieve/*` and
`/data/update/*`, and is planned for `/tool/generate-tile/*`.

Route behaviour:

- `/tool/align/*` serves `resources/align.html`, slurped on each request. The
  page reads the image path from its own URL.
- `/data/orthophoto/*` serves the image file (`image/jpeg`).
- `/data/retrieve/*` returns the list entry.
- `/data/update/*` is a **POST** with a JSON body holding the 5 parameters. It
  returns 400 if any is missing or not a number, and otherwise returns the
  updated entry.
- These routes return a JSON 404 for an image that is not in the store.
- A `compojure.route/not-found` fallback is required. Without it, an unmatched
  route makes Jetty fail with a 500 "Response map is nil".

`/data/add/*` (GET, in the clj-scheduler style):

- returns 404 with a JSON `error` if the file does not exist;
- otherwise extracts metadata, creates initial parameters (defaults for
  anything missing), stores the image, and returns its list entry;
- re-adding an image resets its parameters.

`/data/list` returns one entry per image with these fields:

- `id`, `path`;
- `parameters`;
- `parameters-set`: false while the parameters are still the EXIF ones;
- `updated`;
- `tilted`: true, false, or nil when unknown;
- `gimbal-pitch`.

## Parameters

| Name               | Unit    | Meaning                                              |
|--------------------|---------|------------------------------------------------------|
| `longitude-center` | degrees | Image center                                         |
| `latitude-center`  | degrees | Image center                                         |
| `longitude-span`   | degrees | Image width, **before rotation**                     |
| `latitude-span`    | degrees | Image height, **before rotation**                    |
| `rotation`         | degrees | **Clockwise**, around the center; 0 = north-up        |

Initial values are taken from EXIF as far as possible. This is implemented in
`orthophoto-king.metadata` (see "Code").

## Client functions (controls)

| Control         | Default step                 |
|-----------------|------------------------------|
| width `+` / `-` | 1% of current `longitude-span` |
| height `+` / `-`| 1% of current `latitude-span`  |
| rotate `+` / `-`| 0.5° (`+` = clockwise)        |

These are starting defaults that we will adjust. (Centering is done by setting
longitude / latitude.)

## Workflow

1. The server has the current drone image. The client opens `/tool/align`.
2. The client fetches the image (`/data/orthophoto`) and its parameters
   (`/data/retrieve`). The first time, the parameters come from EXIF.
3. The user adjusts center, width/height span and rotation over the base map.
4. The user submits, and the client sends the parameters to `/data/update`. The
   server writes them to `store.edn`.
5. The user requests tile generation with `/tool/generate-tile`. The server
   runs the tile job, whose inputs are the image path, the parameters and the
   tiles output path. It writes 256x256 PNG tiles as `{z}/{x}/{y}.png` under
   the output path. The tiles are only stored, never served.

### Zoom levels (derived from span)

The zoom range is not configured; it is calculated from the span.

- **Max zoom** is the zoom whose tile resolution first reaches the image's own
  resolution:
  `ceil(log2(360 * image-width-px / (256 * longitude-span)))`.
- **Min zoom** is the zoom at which the whole image is about 256x256 px, i.e.
  roughly one tile:
  `floor(log2(360 / max(longitude-span, latitude-span / cos(latitude-center))))`.
- Tiles are generated for every zoom from min to max, so one image produces
  many tiles per zoom.

### Rotation geometry (decided)

Corners are computed in a local frame where longitude offsets are scaled by
`cos(latitude-center)`. The corners are rotated clockwise around the center in
that frame, then converted back to degrees. This keeps a rotated image
rectangular on the map. The same math is used both for the 3 corners passed to
`ImageOverlay.Rotated` and by the tile job.

### Tilted images

The span estimate assumes a nadir (straight-down) shot. An image counts as
tilted when `|gimbal-pitch + 90| > 5°`
(`orthophoto-king.metadata/tilt-threshold-degrees`). Tilted images are reported
in `/data/list`.

### Tile job inputs

The job takes the image path, the parameters and the tiles output path. The
**output path is not stored in state**; it is supplied when the job is
scheduled. How it is supplied is still to be decided.

## Code

| File                                   | Content                                  |
|----------------------------------------|------------------------------------------|
| `project.clj`                          | lein project. Deps: clojure 1.10.1, metadata-extractor 2.14.0, clj-common 0.3.3 |
| `src/orthophoto_king/metadata.clj`     | EXIF/XMP → initial parameters, `tilted?` |
| `src/orthophoto_king/store.clj`        | `store.edn` read/write, image registry   |
| `src/orthophoto_king/core.clj`         | HTTP server on port 6080; `handler`, `start` |
| `resources/align.html`                 | Align tool page (Leaflet 1.9.4 + ImageOverlay.Rotated 0.2.1 from unpkg) |

### Align page (`resources/align.html`)

- Map on the left, panel on the right. Base layers: Google Satellite (the
  default) and OSM.
- The overlay is drawn with the 3 corners from `corners()`, the JS version of
  the "Rotation geometry" math.
- The center can be edited directly, or set with "move to map center".
- Width and height buttons change the span by a step % (default 1); rotation
  buttons change it by a step ° (default 0.5). Both steps can be edited on the
  page.
- There is an opacity slider (default 0.7).
- "reset" reloads the parameters from the server; "submit" POSTs them.
- Tilted images show a red warning.
- `?tile=<base>` adds an extra XYZ tile layer above the base layer and below
  the drone image (it is listed in the layers control as "tile").
  - A base URL gets `/{z}/{x}/{y}.png` appended; a value that already contains
    `{z}` is used as a full template.
  - The base must name the layer in full. For example, trek-mate serves
    `/tile/:type/:name/...`, so the parameter is
    `http://localhost:6070/tile/trek-mate/<name>`; nothing is appended to the
    name.
  - No CORS is needed, because tiles load as `<img>`.
  - All layers allow zoom 22 regardless of `tile`, because alignment needs
    deep zoom. OSM upscales past 19 (`maxNativeZoom`) and Google past 21.

### `orthophoto-king.store`

- `store-path` is `["Users" "vanja" "dataset-git" "orthophoto" "store.edn"]`.
  It is read once into the `store` atom, and every change is written back with
  pprint.
- Shape:
  `{:images {[path] {:id "/path/string" :image [path] :metadata {...} :parameters {...} :updated millis-or-nil}}}`.
- Images are keyed by their path vector; `:id` is the same path as a string.
- Functions:
  - `image-add path` extracts metadata and initial parameters, re-adding an
    image resets it, and it returns the stored image;
  - `image-list`, `image-get path`;
  - `parameters-set path parameters` also sets `:updated`.
- Images are added with `/data/add/*` or from the REPL; there is no upload.
- `metadata/extract` returns `{}` if the metadata cannot be read. In that case
  all parameters are defaults.

### `orthophoto-king.metadata`

This namespace is based on `photo-map.core/extract-image-info` in
`~/projects/photo-map`.

- `extract image-path` returns raw values, any of which may be nil:
  - `:longitude`, `:latitude`, `:altitude` (GPS; above sea level, not used);
  - `:relative-altitude`, `:gimbal-yaw`, `:gimbal-pitch`, `:flight-yaw` (DJI
    XMP `drone-dji:*`);
  - `:focal-length-35mm` (EXIF);
  - `:width`, `:height` (JPEG, falling back to EXIF).
- `ground-size` computes `[width-m height-m]` for a nadir image as
  `diagonal = relative-altitude * 43.27 / focal-length-35mm`, split by the
  pixel aspect ratio.
- `metadata->parameters` builds the parameters:
  - spans in degrees, with `latitude-span = height-m / 111320` and
    `longitude-span = width-m / (111320 * cos(lat))`;
  - `rotation = gimbal-yaw`, else `flight-yaw`, else 0, normalized to
    [0, 360).
- Defaults when data is missing: center 0,0, a 100 m wide span (the height
  keeps the aspect ratio), rotation 0.
- `image->parameters image-path` runs `extract` and then
  `metadata->parameters`.

## Libraries (findings from local clones in `~/projects`)

### clj-scheduler (`com.mungolab/clj-scheduler "0.1.0"`). Not a dependency, reference only.

- Depends on `clj-common "0.3.2"` and `clj-geo "0.2.0"`.
- Loading `clj-scheduler.core` **starts two threads**: `main-worker`, which
  runs waiting jobs one at a time, and `trigger-thread`.
- Job API:
  `(core/job-sumbit (core/job-create name configuration (fn [context] ...)))`
  (note the spelling: `job-sumbit`). It returns the job id. Status goes
  `:waiting` → `:running` → `:finished` / `:failed`.
- Jobs use `clj-common.context` for reporting: `context/trace`,
  `context/counter`, `context/configuration`, `context/store-get`,
  `context/store-set`.
- It has its own persistent state file, `clj-scheduler.env/state-path`. The
  path comes from the `CLJ_SCHEDULER_STATE_PATH` env var, with default
  `/home/ec2-user/clj-scheduler-state.edn`. This is separate from our
  `store.edn`.
- `clj-scheduler.env` defines `dataset-git-path`, from env `DATASET_GIT` with
  default `/Users/vanja/dataset-git`.
- `clj-scheduler.main` runs its own HTTP UI on port 7076.

### clj-common (`com.mungolab/clj-common "0.3.3"` locally)

- `clj-common.http-server/create-server [port handler]` starts Ring Jetty. It
  binds to `127.0.0.1` by default; pass `{:host "0.0.0.0"}` to bind all
  interfaces. Calling it again on the same port replaces the server.
- It pulls in compojure, ring (core, jetty, json), and hiccup.
- `clj-common.edn`: `read-object` / `write-object` / `write-pprint-object`
  over streams. `clj-common.localfs` provides file IO.

### clj-geo (`com.mungolab/clj-geo "0.2.0"`)

- `clj-geo.math.tile` has Web Mercator slippy-tile math. Tiles are
  `[zoom x y]` and locations are `{:longitude :latitude}`.
  - `zoom->location->tile`, `tile->location` (upper-left corner),
    `tile->location-bounds` → `[min-lon max-lon min-lat max-lat]`.
  - `calculate-tile-seq zoom min-lon max-lon min-lat max-lat`.
- These are useful for the tile-generation job. clj-geo is **not** a
  dependency because it pulls in geotools. Copy the needed functions instead.

## Working conventions

- Do not compile, build, run or test anything unless the user explicitly asks in
  that message. The user runs things and reports errors back. Reason about
  correctness by reading the code.

## Open questions

Not decided yet. Ask the user, and do not assume. Move each answer to the
decisions log once it is settled.

1. **Tile job scheduling:** how is the tiles output path supplied to
   `/tool/generate-tile/*` (query param, a fixed root plus the image path, ...)?
2. **Tilt threshold:** 5° is a default; adjust as needed.

## Decisions log

Record dated decisions and findings here.

- 2026-10-10: Project started. The repository was empty. This file was created
  from the initial specification.
- 2026-10-10: Decisions:
  - One image at a time.
  - Spans are before rotation, in degrees. Rotation is in degrees, clockwise.
  - Take as much from EXIF as possible.
  - Use the Leaflet.ImageOverlay.Rotated plugin.
  - Tiles are 256x256 PNG in a z/x/y structure.
  - Use vanjakom/clj-scheduler, triggered on user request via the new route
    `/tool/generate-tile`.
  - Storage is a single file, `~/dataset-git/orthophoto/store.edn`.
  - Build with Leiningen, plus vanjakom/clj-common.
  - Google Satellite URL is fixed (see "Base layers"). Bing is removed for now.
  - Step sizes use defaults, to be adjusted later.
- 2026-10-10: Read local clones of clj-scheduler, clj-common and clj-geo (see
  "Libraries"). `~/dataset-git/orthophoto/` does not exist yet.
- 2026-10-10: Decisions:
  - Everything lives on the local fs, and paths are clj-common vectors.
  - Tiles are only stored, never served.
  - The job takes the image, the parameters and the tiles output path.
  - Zoom is derived from the span.
  - Our libraries are linked in `checkouts/`, as in uberjvm.
  - clj-scheduler is not a dependency; `clj-common.context` is enough to write
    the job.
  - HTTP port is 6080.
  - Metadata extraction is our own util; copying code from other projects is
    fine.
  - Added `project.clj`, `.gitignore` (copied from uberjvm),
    `checkouts/clj-common`, and `src/orthophoto_king/metadata.clj`. None of
    this has been compiled yet.
- 2026-10-10: Decisions:
  - Tile layout is `{z}/{x}/{y}.png` (confirmed).
  - The rotation proposal is accepted (see "Rotation geometry").
  - Min zoom is where the image is about 256x256 px; all zooms from min to max
    are generated.
  - The tiles output path is not stored in state.
  - Tilted images are warned about.
  - New route `/data/list` lists images, their parameters, and whether each is
    tilted.
  - Added `store.clj` and `core.clj` (`/data/list` only so far) and
    `metadata/tilted?`. Not compiled yet.
  - My assumptions, flagged above: `?id=` image selection, REPL `image-add`.
- 2026-10-10: Decisions:
  - The image id is the image path; routes carry it as the URL rest (`/*`).
  - Adding an image creates initial parameters, with defaults where EXIF is
    missing.
  - New route `/data/add/*`.
  - Metadata read failures now fall back to defaults instead of throwing.
  - Store is keyed by path vector. Not compiled yet.
- 2026-10-10: First real image added with `/data/add/*`. EXIF extraction
  gave a center, a span estimated from altitude and focal length (16:9, not the
  default), rotation, and gimbal pitch -90 (not tilted). This confirms the
  metadata → parameters → store path works end to end. URL-encoded spaces in
  the path work.
- 2026-10-10: Opening `/tool/align/...` gave a 500 "Response map is nil",
  because the route did not exist and there was no fallback. Fixed by adding:
  - the `/tool/align/*` page;
  - `/data/orthophoto/*`, `/data/retrieve/*` and `/data/update/*` (POST JSON);
  - a not-found fallback.
  Not compiled yet.
- 2026-10-10: Reloaded `orthophoto-king.core` over nREPL (port from
  `.nrepl-port`; the user allows connecting to the REPL and reloading).
  - Verified against the running server: retrieve 200, orthophoto 200
    (image/jpeg), update with bad body 400, unknown route 404.
  - `/tool/align/*` still fails with a 500: `(io/resource "align.html")` is
    nil because `resources/` did not exist when the REPL started, so it is not
    on the classpath. A REPL restart is needed.
- 2026-10-10: The user restarted and reloaded the REPL; `/tool/align/*` now
  serves the page (200, text/html).
- 2026-10-13: Added the `?tile=` URL parameter for an extra tile layer on the
  align page. Followed the user's spec, except:
  - the existing layers control and Google Satellite default are kept, and the
    tile layer is listed there as an overlay;
  - zoom 22 stays on whether or not `tile` is given.
- 2026-10-19: Added the "Public repository" note: no personal data in
  committed files. Removed the real image path, coordinates and dataset name
  from this file. Commit 4fe4b3a (already pushed) still contains them.
