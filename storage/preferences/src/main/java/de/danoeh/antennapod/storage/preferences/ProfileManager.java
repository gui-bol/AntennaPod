package de.danoeh.antennapod.storage.preferences;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Fork Balado : profils locaux séparés. Chaque profil possède sa propre base de données,
 * ses propres fichiers SharedPreferences et son propre dossier de données. Le profil 1
 * conserve les noms historiques d'AntennaPod, si bien qu'une installation existante
 * (ou une base restaurée depuis AntennaPod stock) devient simplement le profil 1.
 *
 * <p>Doit être initialisé avant tout autre composant — voir ClientConfigurator. Le
 * changement de profil actif ne prend effet qu'au redémarrage complet du process :
 * tous les singletons de l'app (base, prefs, lecteur) sont initialisés une seule fois.</p>
 */
public abstract class ProfileManager {
    public static final int DEFAULT_PROFILE_ID = 1;
    /** Fichier de prefs global (jamais scopé par profil) contenant le registre des profils. */
    private static final String PREFS_NAME = "profiles";
    private static final String PREF_ACTIVE_PROFILE = "activeProfileId";
    private static final String PREF_PROFILE_IDS = "profileIds";
    private static final String PREF_NAME_PREFIX = "profileName_";
    private static final String LEGACY_DATABASE_NAME = "Antennapod.db";

    private static SharedPreferences prefs;
    private static int activeProfileId = DEFAULT_PROFILE_ID;

    public static void init(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(PREF_PROFILE_IDS)) {
            prefs.edit()
                    .putString(PREF_PROFILE_IDS, String.valueOf(DEFAULT_PROFILE_ID))
                    .putInt(PREF_ACTIVE_PROFILE, DEFAULT_PROFILE_ID)
                    .apply();
        }
        activeProfileId = prefs.getInt(PREF_ACTIVE_PROFILE, DEFAULT_PROFILE_ID);
        if (!getProfileIds().contains(activeProfileId)) {
            activeProfileId = DEFAULT_PROFILE_ID;
            prefs.edit().putInt(PREF_ACTIVE_PROFILE, DEFAULT_PROFILE_ID).apply();
        }
    }

    public static int getActiveProfileId() {
        return activeProfileId;
    }

    @NonNull
    public static List<Integer> getProfileIds() {
        List<Integer> ids = new ArrayList<>();
        String stored = prefs.getString(PREF_PROFILE_IDS, String.valueOf(DEFAULT_PROFILE_ID));
        for (String part : stored.split(",")) {
            if (!part.trim().isEmpty()) {
                ids.add(Integer.parseInt(part.trim()));
            }
        }
        return ids;
    }

    @NonNull
    public static String getProfileName(int profileId) {
        return prefs.getString(PREF_NAME_PREFIX + profileId, "Profil " + profileId);
    }

    public static void setProfileName(int profileId, @NonNull String name) {
        prefs.edit().putString(PREF_NAME_PREFIX + profileId, name).apply();
    }

    /** Crée un profil et retourne son id. Les ids ne sont jamais réutilisés. */
    public static int addProfile(@NonNull String name) {
        List<Integer> ids = getProfileIds();
        int nextId = prefs.getInt("nextProfileId", 0);
        for (Integer id : ids) {
            nextId = Math.max(nextId, id + 1);
        }
        ids.add(nextId);
        StringBuilder sb = new StringBuilder();
        for (Integer id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        prefs.edit()
                .putString(PREF_PROFILE_IDS, sb.toString())
                .putString(PREF_NAME_PREFIX + nextId, name)
                .putInt("nextProfileId", nextId + 1)
                .apply();
        return nextId;
    }

    /**
     * Retire un profil du registre et supprime (best effort) sa base, ses fichiers de
     * prefs et son dossier de données. Refusé pour le profil actif.
     */
    public static void removeProfile(@NonNull Context context, int profileId) {
        if (profileId == activeProfileId) {
            throw new IllegalArgumentException("Cannot remove the active profile");
        }
        List<Integer> ids = getProfileIds();
        ids.remove(Integer.valueOf(profileId));
        StringBuilder sb = new StringBuilder();
        for (Integer id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        prefs.edit()
                .putString(PREF_PROFILE_IDS, sb.toString())
                .remove(PREF_NAME_PREFIX + profileId)
                .apply();
        deleteProfileData(context, profileId);
    }

    /**
     * Marque le profil comme actif pour le prochain démarrage du process. L'appelant est
     * responsable d'arrêter la lecture, d'annuler les workers puis de redémarrer l'app.
     */
    public static void setActiveProfileId(int profileId) {
        if (!getProfileIds().contains(profileId)) {
            throw new IllegalArgumentException("Unknown profile: " + profileId);
        }
        prefs.edit().putInt(PREF_ACTIVE_PROFILE, profileId).commit();
    }

    @NonNull
    public static String getDatabaseName() {
        return getDatabaseName(activeProfileId);
    }

    @NonNull
    public static String getDatabaseName(int profileId) {
        return profileId == DEFAULT_PROFILE_ID ? LEGACY_DATABASE_NAME : "Antennapod_p" + profileId + ".db";
    }

    /** Scope un nom de fichier SharedPreferences sur le profil actif (identité pour le profil 1). */
    @NonNull
    public static String scopedPrefsName(@NonNull String baseName) {
        return activeProfileId == DEFAULT_PROFILE_ID ? baseName : baseName + "_p" + activeProfileId;
    }

    /** Nom du fichier de prefs par défaut (équivalent PreferenceManager) scopé sur le profil actif. */
    @NonNull
    public static String getDefaultPrefsName(@NonNull Context context) {
        return scopedPrefsName(context.getPackageName() + "_preferences");
    }

    /** Remplaçant profile-aware de PreferenceManager.getDefaultSharedPreferences. */
    @NonNull
    public static SharedPreferences getDefaultSharedPreferences(@NonNull Context context) {
        return context.getSharedPreferences(getDefaultPrefsName(context), Context.MODE_PRIVATE);
    }

    /**
     * Scope un sous-dossier du dossier de données sur le profil actif.
     * Profil 1 : identité. Autres profils : "profiles/N[/type]".
     */
    @Nullable
    public static String scopedDataFolderType(@Nullable String type) {
        if (activeProfileId == DEFAULT_PROFILE_ID) {
            return type;
        }
        String base = "profiles/" + activeProfileId;
        return type == null ? base : base + "/" + type;
    }

    private static void deleteProfileData(@NonNull Context context, int profileId) {
        if (profileId == DEFAULT_PROFILE_ID) {
            return; // le profil 1 porte les noms legacy : jamais supprimé automatiquement
        }
        context.deleteDatabase(getDatabaseName(profileId));
        File sharedPrefsDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        File[] prefFiles = sharedPrefsDir.listFiles();
        if (prefFiles != null) {
            for (File file : prefFiles) {
                if (file.getName().endsWith("_p" + profileId + ".xml")) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
        deleteRecursively(new File(context.getExternalFilesDir(null), "profiles/" + profileId));
        deleteRecursively(new File(context.getFilesDir(), "profiles/" + profileId));
    }

    private static void deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
