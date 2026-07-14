# OAuth Pages (Melodie)

Pages statiques pretes pour l'ecran de consentement Google OAuth:

- `index.html` -> page d'accueil de l'application
- `privacy.html` -> regles de confidentialite
- `terms.html` -> conditions d'utilisation

## Publication rapide (GitHub Pages)

1. Cree un repository public (ou utilise celui-ci).
2. Place ce dossier a la racine du repo.
3. Active GitHub Pages (Settings -> Pages) sur la branche principale.
4. Utilise les URLs publiees dans Google Auth Platform:
   - Home page: `https://<user>.github.io/<repo>/oauth-pages/index.html`
   - Privacy policy: `https://<user>.github.io/<repo>/oauth-pages/privacy.html`
   - Terms of service: `https://<user>.github.io/<repo>/oauth-pages/terms.html`

## Publication rapide (Firebase Hosting)

Si tu preferes Firebase Hosting, deploie ce dossier comme contenu statique et renseigne les 3 URLs equivalentes.

## Notes

- Garde les pages accessibles publiquement (sans authentification).
- Mets a jour la date de modification si tu changes les textes.

