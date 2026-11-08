#!/usr/bin/env python3
"""Non-build wallpaper source/resource contracts. Does not compile or exercise Android UI."""

from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ANDROID = Path(__file__).resolve().parents[1]
REPO = ANDROID.parents[1]
MAIN = ANDROID / "app/src/main"
JAVA = MAIN / "java/org/yuzu/yuzu_emu"
RES = MAIN / "res"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
APP_NS = "{http://schemas.android.com/apk/res-auto}"


def parse_resource(path):
    text = path.read_text(encoding="utf-8")
    if len(text) > 2 * 1024 * 1024 or "<!DOCTYPE" in text.upper() or "<!ENTITY" in text.upper():
        raise ValueError(f"Unsupported XML declarations or resource size: {path}")
    return ET.fromstring(text)


class WallpaperSourceContracts(unittest.TestCase):
    def test_wallpaper_xml_is_well_formed(self):
        files = set(RES.glob("layout*/*wallpaper*.xml"))
        files.update(RES.glob("layout*/item_online_background.xml"))
        files.update(RES.glob("values*/wallpapers.xml"))
        files.add(RES / "navigation/settings_subscreen_navigation.xml")
        self.assertGreaterEqual(len(files), 3)
        for path in sorted(files):
            with self.subTest(file=path.name):
                parse_resource(path)

    def test_wallpaper_setting_registration_and_defaults(self):
        native = (REPO / "src/common/settings.h").read_text(encoding="utf-8")
        int_settings = (JAVA / "features/settings/model/IntSetting.kt").read_text(encoding="utf-8")
        bool_settings = (JAVA / "features/settings/model/BooleanSetting.kt").read_text(encoding="utf-8")
        defaults = {"fit": 0, "dim": 30, "blur": 12, "zoom": 100, "x": 50, "y": 50}
        for suffix, default in defaults.items():
            key = f"library_background_{suffix}"
            with self.subTest(key=key):
                self.assertIn(f'("{key}")', int_settings)
                self.assertRegex(native, rf'\b{key}\s*\{{linkage,\s*{default},\s*"{key}"')
        self.assertIn('("wallpaper_recent_games")', bool_settings)
        self.assertRegex(native, r'wallpaper_recent_games\s*\{linkage,\s*false,')
        bridge = (MAIN / "jni/android_config.cpp").read_text(encoding="utf-8")
        self.assertIn("ReadCategory(Settings::Category::Ui);", bridge)
        self.assertIn("WriteCategory(Settings::Category::Ui);", bridge)

    def test_local_resource_references_exist(self):
        names = {}
        for directory in RES.iterdir():
            if not directory.is_dir():
                continue
            kind = directory.name.split("-")[0]
            for path in directory.glob("*"):
                if kind == "values" and path.suffix == ".xml":
                    for node in parse_resource(path):
                        name = node.get("name")
                        if name:
                            resource_kind = node.get("type") if node.tag == "item" else node.tag
                            if resource_kind in ("string-array", "integer-array", "array"):
                                resource_kind = "array"
                            names.setdefault(resource_kind, set()).add(name.replace(".", "_"))
                elif path.is_file():
                    names.setdefault(kind, set()).add(path.stem)
                if path.suffix == ".xml":
                    text = path.read_text(encoding="utf-8")
                    names.setdefault("id", set()).update(re.findall(r'@\+id/(\w+)', text))
        sources = list((JAVA / "utils/background").glob("Wallpaper*.kt"))
        sources += list((JAVA / "features/settings/ui").glob("Wallpaper*.kt"))
        sources += list((JAVA / "fragments").glob("Wallpaper*.kt"))
        sources += [JAVA / "utils/LibraryBackgroundApplier.kt", JAVA / "features/settings/ui/OnlineBackgroundAdapter.kt"]
        for path in sources:
            text = path.read_text(encoding="utf-8")
            for kind, name in re.findall(
                r'(?<![\w.])R\.(string|id|layout|drawable|style|color|array|dimen)\.(\w+)', text
            ):
                with self.subTest(file=path.name, resource=f"{kind}/{name}"):
                    self.assertIn(name, names.get(kind, set()))

    def test_view_binding_fields_match_layouts(self):
        pairs = {
            "WallpaperHubFragment.kt": "fragment_wallpaper_hub.xml",
            "WallpaperSearchFragment.kt": "fragment_wallpaper_search.xml",
            "WallpaperEditorFragment.kt": "fragment_wallpaper_editor.xml",
        }
        included = {
            "wallpaper_preview_card": "wallpaper_preview.xml",
        }
        for source_name, layout_name in pairs.items():
            text = (JAVA / "fragments" / source_name).read_text(encoding="utf-8")
            layout = parse_resource(RES / "layout" / layout_name)
            ids = {
                node.get(ANDROID_NS + "id").replace("@+id/", "")
                for node in layout.iter() if node.get(ANDROID_NS + "id")
            }
            for included_id, included_layout in included.items():
                if included_id in ids:
                    child = parse_resource(RES / "layout" / included_layout)
                    ids.update(
                        node.get(ANDROID_NS + "id").replace("@+id/", "")
                        for node in child.iter() if node.get(ANDROID_NS + "id")
                    )
            fields = set(re.findall(r'\bbinding\.(\w+)', text))
            for field in fields:
                if field in ("root",):
                    continue
                snake = re.sub(r'(?<!^)(?=[A-Z])', '_', field).lower()
                with self.subTest(file=source_name, field=field):
                    self.assertIn(snake, ids)
            for binding_name in re.findall(r'import org\.yuzu\.yuzu_emu\.databinding\.(\w+)Binding', text):
                expected = re.sub(r'(?<!^)(?=[A-Z])', '_', binding_name).lower() + ".xml"
                with self.subTest(file=source_name, binding=binding_name):
                    self.assertTrue((RES / "layout" / expected).exists() or
                                    (RES / "layout-w600dp" / expected).exists(), expected)

    def test_navigation_layout_previews_exist(self):
        nav = parse_resource(RES / "navigation/settings_subscreen_navigation.xml")
        for node in nav.iter():
            layout = node.get("{http://schemas.android.com/tools}layout")
            if layout:
                name = layout.replace("@layout/", "") + ".xml"
                with self.subTest(destination=node.get(ANDROID_NS + "id"), layout=name):
                    self.assertTrue((RES / "layout" / name).exists() or
                                    (RES / "layout-w600dp" / name).exists(), name)

    def test_navigation_targets_and_actions_resolve(self):
        nav = parse_resource(RES / "navigation/settings_subscreen_navigation.xml")
        ids = {
            node.get(ANDROID_NS + "id").replace("@+id/", "")
            for node in nav.iter() if node.get(ANDROID_NS + "id")
        }
        self.assertIn("wallpaper_graph", ids)
        for node in nav.iter():
            name = node.get(ANDROID_NS + "name")
            if name and node.tag in ("fragment", "dialog", "activity"):
                path = MAIN / "java" / Path(*name.split("."))
                with self.subTest(destination=name):
                    self.assertTrue(path.with_suffix(".kt").exists(), name)
            for key in (APP_NS + "destination", APP_NS + "startDestination"):
                destination = node.get(key)
                if destination:
                    with self.subTest(action=node.get(ANDROID_NS + "id"), target=destination):
                        self.assertIn(destination.replace("@id/", ""), ids)
        graph = [node for node in nav.iter() if node.get(ANDROID_NS + "id") == "@+id/wallpaper_graph"]
        self.assertEqual(1, len(graph), "wallpaper_graph must exist exactly once")
        self.assertEqual("@id/wallpaperHubFragment", graph[0].get(APP_NS + "startDestination"))
        activity = (JAVA / "features/settings/ui/SettingsSubscreenActivity.kt").read_text(encoding="utf-8")
        self.assertIn("WALLPAPERS,", activity)
        self.assertIn("SettingsSubscreen.WALLPAPERS -> R.id.wallpaper_graph", activity)

    def test_old_dialog_is_out_of_the_home_flow(self):
        home = (JAVA / "fragments/HomeSettingsFragment.kt").read_text(encoding="utf-8")
        self.assertNotIn("OnlineBackgroundPickerDialog", home)
        self.assertIn("SettingsSubscreen.WALLPAPERS", home)
        self.assertNotIn("showBlurDialog", home)

    def test_editor_has_side_by_side_tablet_variant(self):
        phone = parse_resource(RES / "layout/fragment_wallpaper_editor.xml")
        tablet = parse_resource(RES / "layout-w600dp/fragment_wallpaper_editor.xml")
        self.assertIsNotNone(phone)
        self.assertIsNotNone(tablet)
        self.assertTrue((RES / "layout-w600dp/fragment_wallpaper_editor.xml").exists())

    def test_new_layout_string_and_style_references_exist(self):
        names = {}
        for directory in RES.iterdir():
            if not directory.is_dir() or not directory.name.startswith("values"):
                continue
            for path in directory.glob("*.xml"):
                for node in parse_resource(path):
                    name = node.get("name")
                    if not name:
                        continue
                    kind = node.get("type") if node.tag == "item" else node.tag
                    names.setdefault(kind, set()).add(name.replace(".", "_"))
        layouts = list(RES.glob("layout*/*wallpaper*.xml")) + [RES / "layout/item_online_background.xml"]
        for path in layouts:
            text = path.read_text(encoding="utf-8")
            for name in re.findall(r'@string/(\w+)', text):
                with self.subTest(file=path.name, string=name):
                    self.assertIn(name, names.get("string", set()))
            for name in re.findall(r'@color/(\w+)', text):
                with self.subTest(file=path.name, color=name):
                    self.assertIn(name, names.get("color", set()))
            for name in re.findall(r'@style/(Suyu[\w.]*)', text):
                with self.subTest(file=path.name, style=name):
                    self.assertIn(name.replace(".", "_"), names.get("style", set()))

    def test_new_sources_have_no_trailing_whitespace(self):
        sources = list((JAVA / "utils/background").glob("*.kt"))
        sources += list((JAVA / "fragments").glob("Wallpaper*.kt"))
        sources += list((JAVA / "features/settings/ui").glob("Wallpaper*.kt"))
        sources += list(RES.glob("layout*/*wallpaper*.xml"))
        for path in sources:
            text = path.read_text(encoding="utf-8")
            with self.subTest(file=path.name):
                self.assertTrue(text.endswith("\n"))
                self.assertFalse([line for line in text.splitlines() if line != line.rstrip()])

    def test_shared_renderer_is_used_by_library(self):
        applier = (JAVA / "utils/LibraryBackgroundApplier.kt").read_text(encoding="utf-8")
        self.assertIn("WallpaperRenderer(background, scrim)", applier)
        self.assertNotIn("SCRIM_ALPHA", applier)
        for layout in ("layout", "layout-land"):
            text = (RES / layout / "fragment_games.xml").read_text(encoding="utf-8")
            self.assertIn('@color/suyu_surface', text)
            self.assertIn('@+id/library_background', text)
            self.assertIn('@+id/library_background_scrim', text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
