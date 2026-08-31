package de.danoeh.antennapod.ui.screen.preferences;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import de.danoeh.antennapod.ProfileSwitcher;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.storage.preferences.ProfileManager;
import de.danoeh.antennapod.ui.preferences.screen.AnimatedPreferenceFragment;

/**
 * Fork Balado : gestion des profils locaux — création, renommage, suppression et bascule.
 * Écran construit dynamiquement (la liste des profils n'est pas connue à la compilation).
 */
public class ProfilesPreferencesFragment extends AnimatedPreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
        rebuildList();
    }

    @Override
    public void onStart() {
        super.onStart();
        ((PreferenceActivity) getActivity()).getSupportActionBar().setTitle(R.string.profiles_label);
    }

    private void rebuildList() {
        PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        Context context = requireContext();
        int activeId = ProfileManager.getActiveProfileId();

        for (int profileId : ProfileManager.getProfileIds()) {
            Preference preference = new Preference(context);
            preference.setTitle(ProfileManager.getProfileName(profileId));
            preference.setIcon(R.drawable.ic_supervisor_account);
            preference.setSummary(profileId == activeId
                    ? getString(R.string.profile_active)
                    : getString(R.string.profile_tap_for_actions));
            final int id = profileId;
            preference.setOnPreferenceClickListener(p -> {
                showProfileActions(id);
                return true;
            });
            screen.addPreference(preference);
        }

        Preference addPreference = new Preference(context);
        addPreference.setTitle(R.string.profile_add);
        addPreference.setIcon(R.drawable.ic_add);
        addPreference.setOnPreferenceClickListener(p -> {
            showNameDialog(null);
            return true;
        });
        screen.addPreference(addPreference);
    }

    private void showProfileActions(int profileId) {
        boolean isActive = profileId == ProfileManager.getActiveProfileId();
        String name = ProfileManager.getProfileName(profileId);
        if (isActive) {
            showNameDialog(profileId);
            return;
        }
        String[] actions = new String[] {
                getString(R.string.profile_switch),
                getString(R.string.profile_rename),
                getString(R.string.profile_delete) };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(name)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        confirmSwitch(profileId);
                    } else if (which == 1) {
                        showNameDialog(profileId);
                    } else {
                        confirmDelete(profileId);
                    }
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private void confirmSwitch(int profileId) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_switch)
                .setMessage(getString(R.string.profile_switch_confirm, ProfileManager.getProfileName(profileId)))
                .setPositiveButton(R.string.confirm_label, (dialog, which) ->
                        ProfileSwitcher.switchTo(requireContext(), profileId))
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private void confirmDelete(int profileId) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_delete)
                .setMessage(getString(R.string.profile_delete_confirm, ProfileManager.getProfileName(profileId)))
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    ProfileManager.removeProfile(requireContext(), profileId);
                    rebuildList();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    /** profileId null : création. Sinon : renommage. */
    private void showNameDialog(Integer profileId) {
        Context context = requireContext();
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint(R.string.profile_name_hint);
        if (profileId != null) {
            input.setText(ProfileManager.getProfileName(profileId));
        }
        FrameLayout container = new FrameLayout(context);
        int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
        container.setPadding(padding, 0, padding, 0);
        container.addView(input);

        new MaterialAlertDialogBuilder(context)
                .setTitle(profileId == null ? R.string.profile_add : R.string.profile_rename)
                .setView(container)
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    if (profileId == null) {
                        ProfileManager.addProfile(name);
                    } else {
                        ProfileManager.setProfileName(profileId, name);
                    }
                    rebuildList();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }
}
