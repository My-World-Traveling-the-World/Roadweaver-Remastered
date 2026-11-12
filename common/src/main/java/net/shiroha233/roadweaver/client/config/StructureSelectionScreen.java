package net.shiroha233.roadweaver.client.config;

import dev.architectury.platform.Platform;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.shiroha233.roadweaver.client.config.localization.ChineseRoadWeaverNames;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.structure.StructureDiscoveryService;
import net.shiroha233.roadweaver.config.structure.StructureEntry;
import net.shiroha233.roadweaver.config.structure.StructureSelectionConfig;
import net.shiroha233.roadweaver.config.structure.StructureTagEntry;

import java.util.*;

/**
 * 结构选择界面 - 最复杂的配置界面
 */
public class StructureSelectionScreen extends Screen {
    private static final int BASE_INDENT_TAG = 30;
    private static final int BASE_INDENT_ORPHAN = 10;
    private static final int INDENT_STEP = 15;
    private static final int HEADER_HEIGHT = 72;
    private static final int FOOTER_HEIGHT = 40;
    private static final int CONTENT_MARGIN = 8;
    private static final int MOD_LIST_WIDTH = 190;
    
    private final Screen parent;
    private StructureListWidget listWidget;
    private ModPickerListWidget modWidget;
    private EditBox searchBox;
    private String searchFilter = "";
    private String selectedModId;
    
    private final Set<String> expandedTags = new HashSet<>();
    private final Set<String> expandedPaths = new HashSet<>();

    public StructureSelectionScreen(Screen parent) {
        super(Component.translatable("config.roadweaver.structure_selection.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        searchBox = new EditBox(font, width / 2 - 100, 22, 200, 18,
                Component.translatable("config.roadweaver.structure_selection.search"));
        searchBox.setHint(Component.translatable("config.roadweaver.structure_selection.search.hint"));
        searchBox.setResponder(text -> {
            searchFilter = text.toLowerCase(Locale.ROOT);
            rebuildList();
        });
        addRenderableWidget(searchBox);

        int listTop = HEADER_HEIGHT;
        int listBottom = height - FOOTER_HEIGHT;
        int listHeight = listBottom - listTop;
        int modWidth = Math.min(MOD_LIST_WIDTH, Math.max(150, width / 3));
        int detailLeft = CONTENT_MARGIN + modWidth + CONTENT_MARGIN;
        int detailWidth = Math.max(180, width - detailLeft - CONTENT_MARGIN);

        modWidget = new ModPickerListWidget(minecraft, modWidth, listHeight, listTop);
        modWidget.setLeftPos(CONTENT_MARGIN);
        addRenderableWidget(modWidget);

        listWidget = new StructureListWidget(minecraft, detailWidth, listHeight, listTop);
        listWidget.setLeftPos(detailLeft);
        addRenderableWidget(listWidget);
        rebuildList();

        int buttonY = height - 28;
        int buttonWidth = 80;
        int spacing = 5;
        int totalWidth = buttonWidth * 4 + spacing * 3;
        int startX = (width - totalWidth) / 2;

        addRenderableWidget(Button.builder(
                Component.translatable("config.roadweaver.structure_selection.select_all"),
                btn -> {
                    StructureSelectionConfig.get().enableAll();
                    rebuildList();
                })
                .pos(startX, buttonY).size(buttonWidth, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("config.roadweaver.structure_selection.deselect_all"),
                btn -> {
                    StructureSelectionConfig.get().clearAll();
                    rebuildList();
                })
                .pos(startX + buttonWidth + spacing, buttonY).size(buttonWidth, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("config.roadweaver.structure_selection.default"),
                btn -> {
                    StructureSelectionConfig.get().clearAll();
                    StructureSelectionConfig.get().enableDefaultVillages();
                    rebuildList();
                })
                .pos(startX + (buttonWidth + spacing) * 2, buttonY).size(buttonWidth, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                btn -> onClose())
                .pos(startX + (buttonWidth + spacing) * 3, buttonY).size(buttonWidth, 20).build());
    }

    private void rebuildList() {
        if (listWidget == null || modWidget == null) return;
        modWidget.clearEntries();
        listWidget.clearEntries();

        StructureDiscoveryService.DiscoveryResult result = StructureDiscoveryService.getResult();
        if (result == null) {
            modWidget.doAddEntry(new ModPickerListWidget.ModEntry(
                    modWidget, "", Component.translatable("config.roadweaver.structure_selection.no_data"),
                    0, 0, false, false, false, m -> {}, m -> {}
            ));
            listWidget.doAddEntry(new StructureListWidget.MessageEntry(
                    listWidget,
                    Component.translatable("config.roadweaver.structure_selection.no_data")
            ));
            return;
        }

        StructureSelectionConfig config = StructureSelectionConfig.get();
        Set<String> modIds = new HashSet<>();
        for (StructureTagEntry tag : result.tags()) {
            modIds.add(tag.namespace());
        }
        for (StructureEntry structure : result.allStructures()) {
            modIds.add(structure.namespace());
        }

        Map<String, List<StructureTagEntry>> tagsByMod = new HashMap<>();
        for (StructureTagEntry tag : result.tags()) {
            tagsByMod.computeIfAbsent(tag.namespace(), k -> new ArrayList<>()).add(tag);
        }
        for (List<StructureTagEntry> list : tagsByMod.values()) {
            list.sort(Comparator.comparing(t -> t.displayName().toLowerCase(Locale.ROOT)));
        }

        List<String> sortedModIds = new ArrayList<>(modIds);
        sortedModIds.sort((a, b) -> {
            if (Objects.equals(a, b)) return 0;
            if ("minecraft".equals(a)) return -1;
            if ("minecraft".equals(b)) return 1;
            if ("roadweaver".equals(a)) return -1;
            if ("roadweaver".equals(b)) return 1;
            String na = getModDisplayName(a).toLowerCase(Locale.ROOT);
            String nb = getModDisplayName(b).toLowerCase(Locale.ROOT);
            int cmp = na.compareTo(nb);
            if (cmp != 0) return cmp;
            return a.compareTo(b);
        });

        boolean hasSearch = !searchFilter.isEmpty();
        List<String> visibleModIds = new ArrayList<>();
        Map<String, List<String>> structuresByMod = new HashMap<>();
        for (StructureEntry structure : result.allStructures()) {
            structuresByMod.computeIfAbsent(structure.namespace(), k -> new ArrayList<>())
                    .add(structure.id().toString());
        }

        for (String modId : sortedModIds) {
            boolean modMatchesFilter = !hasSearch || matchesFilter(getModDisplayName(modId)) || matchesFilter(modId);
            boolean contentMatchesFilter = !hasSearch;
            if (hasSearch && !modMatchesFilter) {
                for (StructureEntry structure : result.allStructures()) {
                    if (modId.equals(structure.namespace())
                            && (matchesFilter(structure.displayName()) || matchesFilter(structure.id().toString()))) {
                        contentMatchesFilter = true;
                        break;
                    }
                }
            }
            if (hasSearch && !contentMatchesFilter) {
                for (StructureTagEntry tag : tagsByMod.getOrDefault(modId, Collections.emptyList())) {
                    if (matchesFilter(tag.displayName()) || matchesFilter(tag.tagId().toString())) {
                        contentMatchesFilter = true;
                        break;
                    }
                }
            }
            if (contentMatchesFilter || modMatchesFilter) {
                visibleModIds.add(modId);
            }
        }

        if (visibleModIds.isEmpty()) {
            modWidget.doAddEntry(new ModPickerListWidget.ModEntry(
                    modWidget, "", Component.translatable("config.roadweaver.structure_selection.no_data"),
                    0, 0, false, false, false, m -> {}, m -> {}
            ));
            listWidget.doAddEntry(new StructureListWidget.MessageEntry(
                    listWidget, Component.translatable("config.roadweaver.structure_selection.no_data")));
            return;
        }

        if (selectedModId == null || !visibleModIds.contains(selectedModId)) {
            selectedModId = visibleModIds.get(0);
        }

        for (String modId : visibleModIds) {
            List<String> structuresInMod = structuresByMod.getOrDefault(modId, Collections.emptyList());
            int enabledCount = 0;
            for (String id : structuresInMod) {
                if (config.isStructureEnabled(id)) enabledCount++;
            }
            boolean modAllEnabled = !structuresInMod.isEmpty() && enabledCount == structuresInMod.size();
            boolean modPartialEnabled = enabledCount > 0 && enabledCount < structuresInMod.size();
            modWidget.doAddEntry(new ModPickerListWidget.ModEntry(
                    modWidget, modId,
                    Component.literal(getModDisplayName(modId)),
                    enabledCount, structuresInMod.size(), modAllEnabled, modPartialEnabled,
                    modId.equals(selectedModId), this::onModSelected,
                    m -> onModSelectAll(m, structuresByMod.getOrDefault(m, Collections.emptyList()))
            ));
        }

        rebuildSelectedModDetails(result, tagsByMod, config);
    }

    private void rebuildSelectedModDetails(StructureDiscoveryService.DiscoveryResult result,
                                           Map<String, List<StructureTagEntry>> tagsByMod,
                                           StructureSelectionConfig config) {
        listWidget.clearEntries();
        if (selectedModId == null) return;

        String modId = selectedModId;
        boolean modMatchesFilter = searchFilter.isEmpty()
                || matchesFilter(getModDisplayName(modId)) || matchesFilter(modId);
        Set<String> addedStructures = new HashSet<>();
        List<StructureListWidget.Entry> entries = new ArrayList<>();
        List<StructureTagEntry> modTags = tagsByMod.getOrDefault(modId, Collections.emptyList());

        for (StructureTagEntry tag : modTags) {
            List<StructureEntry> visibleStructures = tag.structures();
            if (visibleStructures.isEmpty()) continue;
            boolean tagMatchesFilter = matchesFilter(tag.displayName()) || matchesFilter(tag.tagId().toString());
            List<StructureEntry> matchingStructures = visibleStructures.stream()
                    .filter(s -> matchesFilter(s.displayName()) || matchesFilter(s.id().toString()))
                    .toList();
            if (!searchFilter.isEmpty() && !modMatchesFilter && !tagMatchesFilter && matchingStructures.isEmpty()) {
                continue;
            }

            boolean isTagEnabled = config.isTagEnabled(tag.tagId().toString());
            boolean isExpanded = searchFilter.isEmpty() ? expandedTags.contains(tag.tagId().toString()) : true;
            int tagSelectedCount = 0;
            for (StructureEntry structure : visibleStructures) {
                if (config.isStructureEnabled(structure.id().toString())) tagSelectedCount++;
            }
            boolean tagAllEnabled = !visibleStructures.isEmpty()
                    && (isTagEnabled || tagSelectedCount == visibleStructures.size());
            boolean tagPartialEnabled = !tagAllEnabled && tagSelectedCount > 0;
            entries.add(new StructureListWidget.TagEntry(
                    listWidget, tag, tagAllEnabled, tagPartialEnabled, tagSelectedCount,
                    visibleStructures.size(), isExpanded, this::onTagToggle, this::onTagExpandToggle));

            if (isExpanded) {
                List<StructureEntry> baseList = (!searchFilter.isEmpty() && !modMatchesFilter && !tagMatchesFilter)
                        ? matchingStructures : new ArrayList<>(visibleStructures);
                addPathNodeEntries(entries, StructurePathNode.buildTree(baseList, tag.namespace()), config, BASE_INDENT_TAG);
            }
            for (StructureEntry structure : visibleStructures) {
                addedStructures.add(structure.id().toString());
            }
        }

        List<StructureEntry> orphanStructures = new ArrayList<>();
        for (StructureEntry structure : result.allStructures()) {
            if (!modId.equals(structure.namespace()) || addedStructures.contains(structure.id().toString())) continue;
            if (!searchFilter.isEmpty() && !modMatchesFilter
                    && !matchesFilter(structure.displayName()) && !matchesFilter(structure.id().toString())) continue;
            orphanStructures.add(structure);
        }
        if (!orphanStructures.isEmpty()) {
            entries.add(new StructureListWidget.HeaderEntry(
                    listWidget, Component.translatable("config.roadweaver.structure_selection.other_structures")));
            addPathNodeEntries(entries, StructurePathNode.buildTree(orphanStructures, modId), config, BASE_INDENT_ORPHAN);
        }

        listWidget.doAddEntry(new StructureListWidget.HeaderEntry(
                listWidget, Component.literal(getModDisplayName(modId) + " [" + modId + "]")));
        for (StructureListWidget.Entry entry : entries) {
            listWidget.doAddEntry(entry);
        }
    }

    private void onModSelectAll(String modId, List<String> structures) {
        StructureSelectionConfig config = StructureSelectionConfig.get();
        boolean allEnabled = true;
        for (String id : structures) {
            if (!config.isStructureEnabled(id)) {
                allEnabled = false;
                break;
            }
        }

        if (allEnabled) {
            for (String id : structures) {
                config.disableStructure(id);
            }
        } else {
            for (String id : structures) {
                config.enableStructure(id);
            }
        }
        rebuildList();
    }

    private void addPathNodeEntries(List<StructureListWidget.Entry> entries,
                                    StructurePathNode node,
                                    StructureSelectionConfig config,
                                    int currentIndent) {
        boolean hasSearch = !searchFilter.isEmpty();

        for (StructurePathNode child : node.children()) {
            if (child.shouldShowAsFolder()) {
                boolean isExpanded = hasSearch || expandedPaths.contains(child.fullPath());

                Set<String> allIds = child.getAllStructureIds();
                int enabledCount = 0;
                for (String id : allIds) {
                    if (config.isStructureEnabled(id)) {
                        enabledCount++;
                    }
                }
                boolean allEnabled = enabledCount == allIds.size() && !allIds.isEmpty();
                boolean partialEnabled = enabledCount > 0 && enabledCount < allIds.size();

                entries.add(new StructureListWidget.PathFolderEntry(
                        listWidget, child, isExpanded, allEnabled, partialEnabled,
                        currentIndent, this::onPathExpandToggle, this::onPathSelectAllToggle
                ));

                if (isExpanded) {
                    addPathNodeEntries(entries, child, config, currentIndent + INDENT_STEP);
                }
            } else {
                addPathNodeEntries(entries, child, config, currentIndent);
            }
        }

        for (StructureEntry structure : node.structures()) {
            if (hasSearch && !matchesFilter(structure.displayName())
                    && !matchesFilter(structure.id().toString())) {
                continue;
            }

            boolean isEnabled = config.isStructureEnabled(structure.id().toString());
            entries.add(new StructureListWidget.PathStructureEntry(
                    listWidget, structure, isEnabled, currentIndent, node.depth(),
                    this::onStructureToggle
            ));
        }
    }

    private void onPathExpandToggle(StructurePathNode pathNode) {
        String path = pathNode.fullPath();
        if (expandedPaths.contains(path)) {
            expandedPaths.remove(path);
        } else {
            expandedPaths.add(path);
        }
        rebuildList();
    }
    
    private void onPathSelectAllToggle(StructurePathNode pathNode) {
        StructureSelectionConfig config = StructureSelectionConfig.get();
        Set<String> allIds = pathNode.getAllStructureIds();
        
        boolean allEnabled = true;
        for (String id : allIds) {
            if (!config.isStructureEnabled(id)) {
                allEnabled = false;
                break;
            }
        }
        
        if (allEnabled) {
            for (String id : allIds) {
                config.disableStructure(id);
            }
        } else {
            for (String id : allIds) {
                config.enableStructure(id);
            }
        }
        rebuildList();
    }
    
    private boolean matchesFilter(String text) {
        if (searchFilter.isEmpty()) return true;
        return text.toLowerCase(Locale.ROOT).contains(searchFilter);
    }
    
    private void onModSelected(String modId) {
        if (modId == null || modId.isEmpty()) return;
        selectedModId = modId;
        rebuildList();
    }
    
    private String getModDisplayName(String modId) {
        if (modId == null || modId.isEmpty()) {
            return ChineseRoadWeaverNames.modName(modId, "unknown");
        }
        if ("minecraft".equals(modId)) {
            return ChineseRoadWeaverNames.modName(modId, "Minecraft");
        }
        if ("roadweaver".equals(modId)) {
            return ChineseRoadWeaverNames.modName(modId, "RoadWeaver");
        }
        return ChineseRoadWeaverNames.modName(modId, Platform.getOptionalMod(modId)
                .map(mod -> mod.getName())
                .orElse(modId));
    }

    private void onTagToggle(StructureTagEntry tag) {
        StructureSelectionConfig config = StructureSelectionConfig.get();
        config.toggleTag(tag.tagId().toString());
        rebuildList();
    }

    private void onTagExpandToggle(StructureTagEntry tag) {
        String tagId = tag.tagId().toString();
        if (expandedTags.contains(tagId)) {
            expandedTags.remove(tagId);
        } else {
            expandedTags.add(tagId);
        }
        rebuildList();
    }

    private void onStructureToggle(StructureEntry structure) {
        StructureSelectionConfig config = StructureSelectionConfig.get();
        config.toggleStructure(structure.id().toString());
        rebuildList();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        StructureSelectionConfig selection = StructureSelectionConfig.get();
        selection.save();

        List<String> whitelist = selection.toWhitelist();
        if (whitelist == null || whitelist.isEmpty()) {
            whitelist = List.of("#minecraft:village");
        }
        ConfigService.get().setStructureWhitelist(whitelist);
        ConfigService.get().setStructureBlacklist(new ArrayList<>(selection.getDisabledStructures()));
        ConfigService.save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox.isFocused()) {
            return searchBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchBox.isFocused()) {
            return searchBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }
}
