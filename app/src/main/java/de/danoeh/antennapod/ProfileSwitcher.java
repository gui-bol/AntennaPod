package de.danoeh.antennapod;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.work.WorkManager;

import de.danoeh.antennapod.playback.service.Media3PlaybackService;
import de.danoeh.antennapod.storage.preferences.ProfileManager;

/**
 * Fork Balado : bascule vers un autre profil par redémarrage complet du process.
 *
 * <p>Séquence : arrêt du service de lecture (sa destruction persiste la position dans la
 * base du profil courant) → annulation de tous les jobs WorkManager (un job en file porte
 * des ids de lignes de l'ancienne base et ne doit jamais s'exécuter sous le nouveau profil ;
 * les workers ont en plus un guard sur le profil stampé) → activation du nouveau profil
 * (commit synchrone) → redémarrage dur. Les workers périodiques (mise à jour des flux,
 * maintenance, export auto) sont ré-armés par MainActivity au prochain démarrage.</p>
 */
public abstract class ProfileSwitcher {

    public static void switchTo(@NonNull Context context, int profileId) {
        if (profileId == ProfileManager.getActiveProfileId()) {
            return;
        }
        Context app = context.getApplicationContext();
        app.stopService(new Intent(app, Media3PlaybackService.class));
        WorkManager.getInstance(app).cancelAllWork();
        ProfileManager.setActiveProfileId(profileId);
        // Le délai laisse le main thread traiter onDestroy() du service (sauvegarde de la
        // position) avant que le process ne soit tué.
        new Handler(Looper.getMainLooper()).postDelayed(() -> forceRestart(app), 500);
    }

    private static void forceRestart(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(context.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        Runtime.getRuntime().exit(0);
    }
}
