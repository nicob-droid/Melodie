package com.melodie.player.ui.drive;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.util.Log;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.melodie.player.R;
import com.melodie.player.data.entity.DriveFolder;
import com.melodie.player.ui.adapter.DriveFolderAdapter;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes;
import com.google.android.gms.common.api.ApiException;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class DriveFragment extends Fragment {

    private DriveViewModel viewModel;
    private DriveFolderAdapter folderAdapter;
    private ProgressBar progressBar;
    private Button syncButton;
    private Button signInButton;
    private ActivityResultLauncher<Intent> signInLauncher;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_drive, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this).get(DriveViewModel.class);

        signInButton = view.findViewById(R.id.btn_sign_in);
        syncButton = view.findViewById(R.id.btn_sync);
        RecyclerView recycler = view.findViewById(R.id.recycler);
        progressBar = view.findViewById(R.id.progress_bar);

        // Setup Activity Result Launcher pour Google Sign-In
        signInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleSignInResult(result));

        // Setup RecyclerView
        folderAdapter = new DriveFolderAdapter(folders -> viewModel.setFolderSelections(folders));

        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(folderAdapter);

        // Bouton Sign In
        signInButton.setOnClickListener(v -> {
            if (viewModel.isLoggedIn()) {
                viewModel.logout();
                signInButton.setText(R.string.drive_sign_in);
                syncButton.setEnabled(false);
            } else {
                startGoogleSignIn();
            }
        });

        // Bouton Sync
        syncButton.setOnClickListener(v -> {
            viewModel.syncSelectedFolders();
            Toast.makeText(requireContext(), R.string.drive_sync_and_add_sources_started, Toast.LENGTH_SHORT).show();
        });

        // Observers
        viewModel.getAuthStatus().observe(getViewLifecycleOwner(), status -> {
            if ("LOGGED_IN".equals(status)) {
                signInButton.setText("Déconnecter");
                syncButton.setEnabled(true);
                viewModel.loadDriveFolders();
            } else if ("DRIVE_API_DISABLED".equals(status)) {
                syncButton.setEnabled(false);
                Toast.makeText(requireContext(),
                        "Google Drive API n'est pas activée dans Google Cloud (drive.googleapis.com).",
                        Toast.LENGTH_LONG).show();
            } else if ("LOGGED_OUT".equals(status)) {
                signInButton.setText(R.string.drive_sign_in);
                syncButton.setEnabled(false);
                folderAdapter.submitFolders(null);
            }
        });

        viewModel.getIsLoading().observe(getViewLifecycleOwner(), isLoading -> {
            progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        });

        viewModel.getDriveFolders().observe(getViewLifecycleOwner(), folders -> {
            if (folders != null) {
                folderAdapter.submitFolders(folders);
            }
        });

        // Vérifier si l'utilisateur est déjà connecté
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(requireContext());
        if (account != null) {
            viewModel.handleGoogleSignInResult(account);
        }
    }

    private void startGoogleSignIn() {
        GoogleSignInClient googleSignInClient = viewModel.getGoogleSignInClient();
        if (googleSignInClient != null) {
            signInLauncher.launch(googleSignInClient.getSignInIntent());
        }
    }

    private void handleSignInResult(ActivityResult result) {
        Intent data = result.getData();
        if (data == null) {
            Toast.makeText(requireContext(), "Connexion annulée (aucune donnée)", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(data)
                    .getResult(ApiException.class);
            if (account != null) {
                viewModel.handleGoogleSignInResult(account);
            } else {
                Toast.makeText(requireContext(), "Connexion Google échouée (compte nul)", Toast.LENGTH_SHORT).show();
            }
        } catch (ApiException e) {
            int code = e.getStatusCode();
            String msg;
            if (code == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
                msg = "Connexion annulée";
            } else if (code == GoogleSignInStatusCodes.SIGN_IN_REQUIRED) {
                msg = "Connexion requise";
            } else if (code == GoogleSignInStatusCodes.NETWORK_ERROR) {
                msg = "Erreur réseau Google Sign-In";
            } else if (code == GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS) {
                msg = "Connexion déjà en cours";
            } else if (code == GoogleSignInStatusCodes.DEVELOPER_ERROR) {
                msg = "Erreur config OAuth (code 10). Vérifie google-services.json, package com.melodie.player et SHA-1 debug/release dans Firebase.";
            } else {
                msg = "Erreur Google Sign-In (code " + code + ")";
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
            int webClientResId = requireContext().getResources()
                    .getIdentifier("default_web_client_id", "string", requireContext().getPackageName());
            Log.e("DriveFragment", "Sign-in failed with code=" + code
                    + ", hasDefaultWebClientId=" + (webClientResId != 0), e);
        }
    }
}


