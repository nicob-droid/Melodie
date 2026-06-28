package com.melodie.player.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.melodie.player.R;
import com.melodie.player.data.entity.DriveFolder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Affiche les dossiers Google Drive sous forme d'arborescence avec deux sections :
 *   • Mon Drive
 *   • Lecteurs partagés
 * Les dossiers sont pliables/dépliables. La case à cocher permet de les sélectionner.
 */
public class DriveFolderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_FOLDER = 1;
    private static final int INDENT_DP   = 20;

    // Clés internes pour les en-têtes de section
    private static final String HEADER_MY_DRIVE = "MY_DRIVE";
    private static final String HEADER_SHARED   = "SHARED";

    // ── Interface publique ──────────────────────────────────────────────────

    public interface OnFolderSelectedListener {
        /** Appelé avec le dossier cliqué + tous ses descendants affectés par la sélection */
        void onFoldersSelected(List<DriveFolder> affected);
    }

    // ── État interne ────────────────────────────────────────────────────────

    private final OnFolderSelectedListener listener;

    /** Liste aplatie affichée dans le RecyclerView (String = header, FolderItem = dossier) */
    private final List<Object> displayItems = new ArrayList<>();

    /** IDs des dossiers actuellement dépliés */
    private final Set<String> expandedIds = new HashSet<>();

    /** Enfants indexés par parentDriveId */
    private final Map<String, List<DriveFolder>> childrenByParentId = new HashMap<>();

    /** Racines Mon Drive */
    private final List<DriveFolder> myDriveRoots = new ArrayList<>();

    /** Racines Lecteurs partagés */
    private final List<DriveFolder> sharedDriveRoots = new ArrayList<>();

    /** Tous les dossiers, dédupliqués, indexés par driveId */
    private Map<String, DriveFolder> allFoldersById = new LinkedHashMap<>();

    // ── Constructeur ────────────────────────────────────────────────────────

    public DriveFolderAdapter(OnFolderSelectedListener listener) {
        this.listener = listener;
    }

    // ── API publique ────────────────────────────────────────────────────────

    public void submitFolders(List<DriveFolder> folders) {
        Set<String> previousExpandedIds = new HashSet<>(expandedIds);
        expandedIds.clear();
        childrenByParentId.clear();
        myDriveRoots.clear();
        sharedDriveRoots.clear();
        allFoldersById.clear();
        displayItems.clear();

        if (folders == null || folders.isEmpty()) {
            notifyDataSetChanged();
            return;
        }

        // 1) Déduplication : on garde le plus récent pour chaque driveId
        Map<String, DriveFolder> unique = new LinkedHashMap<>();
        for (DriveFolder f : folders) {
            DriveFolder ex = unique.get(f.driveId);
            if (ex == null || f.lastSync >= ex.lastSync) unique.put(f.driveId, f);
        }
        allFoldersById = unique;
        Set<String> knownIds = unique.keySet();

        // Préserve les branches déjà ouvertes entre deux emissions LiveData
        // (ex: après sélection d'un parent qui met à jour Room).
        for (String id : previousExpandedIds) {
            if (knownIds.contains(id)) {
                expandedIds.add(id);
            }
        }

        // 2) Classement : racines Mon Drive, racines Shared Drive, enfants
        for (DriveFolder f : unique.values()) {
            String pid = f.parentDriveId == null ? "" : f.parentDriveId.trim();
            boolean isRoot = pid.isEmpty() || !knownIds.contains(pid) || pid.equals(f.driveId);
            if (isRoot) {
                if (f.isSharedDrive) sharedDriveRoots.add(f);
                else myDriveRoots.add(f);
            } else {
                childrenByParentId.computeIfAbsent(pid, k -> new ArrayList<>()).add(f);
            }
        }

        // 3) Tri alphabétique à chaque niveau
        Comparator<DriveFolder> alpha = (a, b) ->
                a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
        myDriveRoots.sort(alpha);
        sharedDriveRoots.sort(alpha);
        for (List<DriveFolder> ch : childrenByParentId.values()) ch.sort(alpha);

        // 4) Premier chargement: tout replié par défaut.
        // Rechargements suivants: l'état d'expansion utilisateur est conservé.

        rebuild();
    }

    // ── Construction interne de la liste aplatie ────────────────────────────

    private void rebuild() {
        displayItems.clear();
        Set<String> visited = new HashSet<>();

        // Section Mon Drive
        if (!myDriveRoots.isEmpty()) {
            displayItems.add(HEADER_MY_DRIVE);
            for (DriveFolder root : myDriveRoots) appendNode(root, 0, visited);
        }

        // Section Lecteurs partagés
        if (!sharedDriveRoots.isEmpty()) {
            displayItems.add(HEADER_SHARED);
            for (DriveFolder root : sharedDriveRoots) appendNode(root, 0, visited);
        }

        // Pas de fallback "orphans" ici : il provoquait des doublons visuels en fin de liste.
        // Les dossiers sont désormais affichés uniquement via les racines déterminées.

        notifyDataSetChanged();
    }

    private void appendNode(DriveFolder folder, int depth, Set<String> visited) {
        if (visited.contains(folder.driveId)) return;
        visited.add(folder.driveId);

        boolean expanded = expandedIds.contains(folder.driveId);
        displayItems.add(new FolderItem(folder, depth, hasChildren(folder.driveId), expanded));

        if (expanded) {
            List<DriveFolder> children = childrenByParentId.get(folder.driveId);
            if (children != null) {
                for (DriveFolder child : children) {
                    DriveFolder fresh = allFoldersById.get(child.driveId);
                    if (fresh != null) appendNode(fresh, depth + 1, visited);
                }
            }
        }
    }

    private boolean hasChildren(String driveId) {
        List<DriveFolder> c = childrenByParentId.get(driveId);
        return c != null && !c.isEmpty();
    }

    private void toggleExpanded(String driveId) {
        if (expandedIds.contains(driveId)) expandedIds.remove(driveId);
        else expandedIds.add(driveId);
        rebuild();
    }

    /**
     * Appelé depuis le ViewHolder quand une checkbox change d'état.
     * Marque aussi tous les descendants pour permettre la synchronisation complète.
     */
    private void onCheckboxChanged(String driveId, boolean checked) {
        List<DriveFolder> affected = new ArrayList<>();
        DriveFolder folder = allFoldersById.get(driveId);
        if (folder != null) {
            folder.selected = checked;
            collectWithDescendants(folder, affected);
        }
        listener.onFoldersSelected(affected);
        // Rafraîchit les checkboxes visibles
        notifyDataSetChanged();
    }

    /**
     * Collecte un dossier et tous ses descendants et les marque avec le même état de sélection.
     */
    private void collectWithDescendants(DriveFolder parent, List<DriveFolder> output) {
        output.add(parent);
        List<DriveFolder> children = childrenByParentId.get(parent.driveId);
        if (children != null) {
            for (DriveFolder child : children) {
                child.selected = parent.selected;
                collectWithDescendants(child, output);
            }
        }
    }

    // ── RecyclerView.Adapter ────────────────────────────────────────────────

    @Override public int getItemCount() { return displayItems.size(); }

    @Override
    public int getItemViewType(int position) {
        return (displayItems.get(position) instanceof FolderItem) ? TYPE_FOLDER : TYPE_HEADER;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER)
            return new HeaderViewHolder(inf.inflate(R.layout.item_drive_header, parent, false));
        return new FolderViewHolder(inf.inflate(R.layout.item_drive_folder, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = displayItems.get(position);
        if (holder instanceof HeaderViewHolder)
            ((HeaderViewHolder) holder).bind((String) item);
        else if (holder instanceof FolderViewHolder)
            ((FolderViewHolder) holder).bind((FolderItem) item, this::toggleExpanded, this::onCheckboxChanged);
    }

    // ── Classes internes ────────────────────────────────────────────────────

    static class FolderItem {
        final DriveFolder folder;
        final int     depth;
        final boolean hasChildren;
        final boolean isExpanded;

        FolderItem(DriveFolder folder, int depth, boolean hasChildren, boolean isExpanded) {
            this.folder      = folder;
            this.depth       = depth;
            this.hasChildren = hasChildren;
            this.isExpanded  = isExpanded;
        }
    }

    /** ViewHolder pour les en-têtes de section */
    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView  title;

        HeaderViewHolder(@NonNull View v) {
            super(v);
            icon  = v.findViewById(R.id.header_icon);
            title = v.findViewById(R.id.header_title);
        }

        void bind(String key) {
            if (HEADER_MY_DRIVE.equals(key)) {
                title.setText(R.string.drive_section_my_drive);
                icon.setImageResource(R.drawable.ic_cloud);
                icon.setColorFilter(ContextCompat.getColor(itemView.getContext(), R.color.audio_cyan));
            } else {
                title.setText(R.string.drive_section_shared);
                icon.setImageResource(R.drawable.ic_folder);
                icon.setColorFilter(ContextCompat.getColor(itemView.getContext(), R.color.melodie_purple));
            }
        }
    }

    /** ViewHolder pour un dossier (ligne de l'arborescence) */
    static class FolderViewHolder extends RecyclerView.ViewHolder {
        private final View      indentSpacer;
        private final TextView  expandIndicator;
        private final TextView  folderName;
        private final CheckBox  checkBox;

        FolderViewHolder(@NonNull View v) {
            super(v);
            indentSpacer     = v.findViewById(R.id.indent_spacer);
            expandIndicator  = v.findViewById(R.id.folder_expand_indicator);
            folderName       = v.findViewById(R.id.folder_name);
            checkBox         = v.findViewById(R.id.folder_checkbox);
        }

        void bind(FolderItem item,
                  Consumer<String> toggleFn,
                  java.util.function.BiConsumer<String, Boolean> selectionFn) {
            DriveFolder folder = item.folder;

            // Indentation selon la profondeur
            float density  = itemView.getResources().getDisplayMetrics().density;
            int   indentPx = Math.round(item.depth * INDENT_DP * density);
            ViewGroup.LayoutParams lp = indentSpacer.getLayoutParams();
            lp.width = indentPx;
            indentSpacer.setLayoutParams(lp);

            // Nom du dossier
            folderName.setText(folder.name);

            // Flèche expand/collapse
            if (item.hasChildren) {
                expandIndicator.setVisibility(View.VISIBLE);
                expandIndicator.setText(item.isExpanded ? "▼" : "▶");
            } else {
                expandIndicator.setVisibility(View.INVISIBLE);
            }

            // Checkbox — sélectionne aussi tous les sous-dossiers
            checkBox.setOnCheckedChangeListener(null);
            checkBox.setChecked(folder.selected);
            checkBox.setOnCheckedChangeListener((btn, checked) ->
                    selectionFn.accept(folder.driveId, checked));

            // Clic sur la ligne : déplier/replier si enfants, sinon cocher
            itemView.setOnClickListener(v -> {
                if (item.hasChildren) toggleFn.accept(folder.driveId);
                else checkBox.toggle();
            });

            // Clic direct sur la flèche
            expandIndicator.setOnClickListener(v -> {
                if (item.hasChildren) toggleFn.accept(folder.driveId);
            });
        }
    }
}
