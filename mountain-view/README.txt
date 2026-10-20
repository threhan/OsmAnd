OsmAnd Mountain View

An embedded Android library based on PeakNav 77383cbf553e02600faedcba75416c621479b342.
The upstream checkout is separate; this module vendors the Android/core subset so clones of
OsmAnd do not require a sibling checkout or a generated binary-only dependency.

Entry points: MountainViewPlugin, main drawer and map-position action menu.
The explicit geo intent carries WGS84 latitude/longitude. A geo query preserves 0,0 too.
The plugin is enabled by default and can be disabled from Plugins.

Architecture: the viewer and its download service run in :mountain_view. The host Application
skips navigation/map initialization in that process only. Memory/configuration callbacks are
guarded there. The viewer has its own task: Back returns to OsmAnd. While downloading, Back
keeps its task/process alive in the background so the foreground download service can finish.
Otherwise final Activity destruction terminates ONLY the named terrain process, releasing
upstream global/native/worker state without touching the host navigation process. Every fresh
entry creates a new renderer; configuration changes listed in the manifest keep the live surface.

Data: files/mountain-view holds persistent downloaded ASTER/OSM tiles; cache/mountain-view
holds temporary imagery; databases/mountain_view_map_db holds download inventory. Original
OsmAnd maps and public-track databases are not converted or modified. The upstream download
provider configuration is preserved. Full GeoNames search index is not bundled: coordinate
entry and online search remain available, but global offline place search is not supplied.
Downloaded regional POIs supply offline peak labels. No terrain/map databases ship in git.

Adaptations: prefixed Android resources; dedicated FileProvider and notification IDs; host-safe
process initialization; private storage with path traversal checks; no upstream global crash
handler; Android platform ICU instead of duplicate ICU4J; common desugaring version; neutral
feature artwork; source and third-party attribution available from About. Android draws native
Chinese peak names with platform fonts. Viewer menus are currently the upstream translations
(English fallback on Chinese devices), while OsmAnd entry labels/help are Chinese/English.

Build with the existing OsmAnd Gradle wrapper and SDK 36/JDK 17:
  gradlew :OsmAnd:assembleAndroidFullOpenglArm64Debug
  gradlew :mountain-view:testDebugUnitTest
Generated native binaries come from pinned libGDX 1.14.2 Maven artifacts for four ABIs; the host
variant filters ABIs. Font is Liberation Sans 2.1.5 (license beside the TTF). SVG interface
artwork and generated PNGs are included; a full upstream Gradle 9 build is not required.
Seven tests cover storage containment, coordinate handoff and PBF runtime coexistence.
See VALIDATION.txt for build, real-data and device results and their limits.

Not implemented here: automatic reuse of OsmAnd OBF/contours/hillshade as PeakNav terrain,
automatic overlay of the complete public-track database, or full Chinese viewer translation.
Users can import a selected GPX using the viewer; the main OsmAnd routing engine is unchanged.
See LICENSE, COPYING, THIRD_PARTY_LICENSES.md and UPSTREAM.txt for provenance and rights.

PBF compatibility: isolateMountainPbf relocates osmpbf 1.6.1 and Protobuf 4.33.2 together
using ASM 9.7.1. The host patched Protobuf stays unchanged. Generated jars stay under build/.
