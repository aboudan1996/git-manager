# Architecture de GitPilot

## 1. Présentation générale

GitPilot est une application de bureau Java construite avec JavaFX. Elle présente une interface
créée directement en Java et stylée avec CSS : les vues sont assemblées dans le code, sans fichiers
FXML. Les opérations Git sont réalisées localement avec JGit, tandis que les fonctions de connexion
GitHub/GitLab utilisent leurs API HTTP pour vérifier le compte et obtenir des identifiants
d'authentification pour les opérations réseau.

L'application est organisée autour de quatre responsabilités :

1. **Démarrage et coordination** : initialiser JavaFX, gérer la fenêtre, les dépôts ouverts, les
   comptes et les opérations utilisateur.
2. **Interface** : construire les vues et traduire les interactions de l'utilisateur en callbacks.
3. **Services** : isoler les opérations Git, les différences, les conflits, les comptes, les
   préférences et les journaux.
4. **Ressources et distribution** : appliquer le thème CSS, afficher l'icône et construire
   l'application distribuable.

Il n'y a pas de serveur applicatif ni de base de données centrale. Les changements Git sont écrits
dans le dépôt local sélectionné; certaines préférences et certains identifiants sont conservés dans
les mécanismes natifs du système.

## 2. Démarrage de l'application

### Classe de lancement

La première classe du projet appelée par la configuration JavaFX est
[`Launcher`](src/main/java/com/git/client/Launcher.java). Son `main` transmet le démarrage à
`Application.launch(GitDeskApplication.class, args)`. `Launcher` est un point d'entrée minimal :
il ne construit pas l'interface et ne contient pas la logique métier.

La configuration du plugin JavaFX dans [`pom.xml`](pom.xml) désigne le module et la classe
`com.git.client/com.git.client.Launcher`. Après le démarrage du runtime JavaFX, celui-ci instancie
[`GitDeskApplication`](src/main/java/com/git/client/GitDeskApplication.java), qui est le contrôleur
principal et le coordinateur de l'application.

### Initialisation JavaFX

JavaFX appelle `GitDeskApplication.start(Stage)` sur son thread d'interface. Cette méthode :

1. enregistre le démarrage et configure les composants qui suivent les dépôts ouverts;
2. crée `RepositoryWorkspaceView` et `GitActionsMenu`, en leur fournissant leurs interfaces de
   callbacks;
3. construit la fenêtre, la barre d'outils et la vue de connexion;
4. charge `styles.css` comme feuille de style de la scène et l'icône depuis les ressources;
5. dimensionne et affiche la fenêtre selon l'écran;
6. restaure et vérifie la session GitHub/GitLab sauvegardée avant de restaurer les dépôts ouverts.

La vue principale n'est donc affichée qu'après la validation ou la création d'une session de compte.
Quand l'authentification réussit, l'application affiche la barre d'outils et l'espace de travail,
puis restaure les dépôts précédemment ouverts.

## 3. Structure du projet

```text
src/
  main/
    java/
      module-info.java
      com/git/client/
        Launcher.java
        GitDeskApplication.java
        RepositoryWorkspaceView.java
        GitActionsMenu.java
        RepositoryTabManager.java
        GitRepositoryService.java
        RepositoryOperations.java
        RepositoryServiceFactory.java
        JGitRepositoryServiceFactory.java
        GitDiffHistoryService.java
        GitConflictResolutionService.java
        ConflictResolutionDocument.java
        WorktreeWatcher.java
        RepositoryChangeMonitor.java
        RepositoryChangeMonitorFactory.java
        NioRepositoryChangeMonitorFactory.java
        GitCredentials.java
        ApplicationLogger.java
        ApplicationEvent.java
        GitAccountService.java
        WindowsCredentialStore.java
        RecentRepositoryStore.java
        LogService.java
        DialogStyler.java
    resources/
      com/git/client/
        styles.css
        gitdesk_icon.png
  test/
    java/com/git/client/
      *Test.java
```

Le projet utilise Java 17 (compilation `--release 17`), JavaFX 21, JGit 7 et JUnit 5. Le descripteur
[`module-info.java`](src/main/java/module-info.java) déclare le module `com.git.client` et ses
dépendances JavaFX, JGit et Java standard.

## 4. Responsabilités des classes

### Démarrage et orchestration

- **`Launcher`** : point d'entrée Java standard utilisé par JavaFX.
- **`GitDeskApplication`** : classe JavaFX principale (`Application`) et racine de composition. Elle
  construit les implémentations par défaut des contrats, compose les vues, gère l'état global et
  relie les interactions de l'interface aux services. Elle coordonne l'ouverture et la sélection des
  dépôts, les opérations asynchrones, les rafraîchissements, les conflits, l'authentification et la
  fermeture.
- **`RepositoryTabManager`** : maintient les onglets de dépôt et associe chaque onglet à son
  `RepositoryOperations`. Il ferme le service quand l'onglet est fermé et notifie l'application des
  changements de sélection.
- **`GitActionsMenu`** : construit le menu des commandes Git et les dialogues associés (branches,
  remotes, clone, opérations sur les commits, etc.). Il ne gère pas lui-même l'accès au dépôt :
  `GitDeskApplication` lui fournit un objet `Actions` qui délègue vers le service courant.

### Interface

- **`RepositoryWorkspaceView`** : construit l'espace de travail JavaFX : historique des commits,
  stashs, recherche, modifications staged/unstaged, conflits, diff et commit. Sa petite interface
  `Actions` transmet les interactions à `GitDeskApplication`, évitant de rendre cette vue
  propriétaire du service Git.
- **`DialogStyler`** : ajoute la feuille `styles.css` et la classe CSS commune aux dialogues.
- **`styles.css`** : définit la palette, les contrôles, états de survol/sélection, listes, barre
  d'outils et dialogues. Les composants et leurs comportements restent créés en Java.
- **`gitdesk_icon.png`** : icône de la fenêtre et de l'application.

### Accès à Git et gestion des différences

- **`GitRepositoryService`** : façade métier autour de JGit pour un dépôt donné. Elle ouvre ou clone
  les dépôts et expose les commandes utilisées par l'interface : état, stage/unstage, commit,
  branches locales/distantes, checkout, merge, rebase, stash, fetch, pull, push, undo/redo,
  historique et conflits. Elle conserve aussi les fournisseurs d'identifiants réseau associés au
  dépôt. Elle implémente `AutoCloseable`; le gestionnaire d'onglets ferme le service avec son dépôt.
- **`RepositoryOperations`** : contrat des opérations d'un dépôt consommées par le coordinateur,
  le gestionnaire d'onglets et le menu. Les modèles de résultat sont définis au niveau du contrat;
  les consommateurs ne dépendent ainsi pas de la classe JGit concrète. La signature propage encore
  `GitAPIException`; l'abstraction n'est donc pas indépendante de toutes les API JGit.
- **`RepositoryServiceFactory`** : contrat de création des services de dépôt;
  `JGitRepositoryServiceFactory` fournit l'implémentation actuellement sélectionnée.
- **`GitDiffHistoryService`** : responsabilité spécialisée de calcul et de rendu des différences.
  Elle produit les diff du worktree, de l'index, des commits, des stashs et le patch des changements
  locaux. `GitRepositoryService` lui délègue ces lectures.
- **`GitConflictResolutionService`** : lit les étapes de l'index JGit (base, côté courant, côté
  entrant), localise les fichiers en conflit et écrit/stage le résultat sélectionné. Il vérifie
  que le chemin reste dans le worktree et refuse les liens symboliques pour éviter de sortir du
  dépôt.
- **`ConflictResolutionDocument`** : transforme les marqueurs textuels en blocs de conflit et
  contexte, retient les choix « current », « incoming » ou « both », puis compose le contenu
  résolu. La vue demande un choix pour chaque bloc avant d'activer **Mark resolved**.

### Détection et persistance

- **`WorktreeWatcher`** : surveille les répertoires du worktree actif avec `WatchService`. Les
  nouveaux dossiers sont enregistrés au fur et à mesure; `.git` est exclu de la surveillance
  générale, mais certains fichiers de métadonnées Git pertinents (HEAD, index, refs, etc.) sont
  suivis spécifiquement. Les événements sont regroupés sur une courte fenêtre avant d'appeler le
  rafraîchissement.
- **`RepositoryChangeMonitor`** et `RepositoryChangeMonitorFactory` : contrats de cycle de vie et
  de création du moniteur; `NioRepositoryChangeMonitorFactory` fournit le watcher NIO du JDK.
- **`RecentRepositoryStore`** : conserve les chemins des dépôts récents, du dernier dépôt et des
  dépôts à restaurer via `java.util.prefs.Preferences`. Il filtre les chemins qui n'existent plus.
- **`WindowsCredentialStore`** : encapsule le Windows Credential Manager pour lire, sauvegarder et
  supprimer des secrets. Les valeurs sont échangées avec un script PowerShell local; la persistance
  est disponible uniquement sous Windows.
- **`GitCredentials`** : interface minimale d'identité et de token utilisée par la couche dépôt;
  `GitAccountService.Account` l'implémente. Un secret est fourni comme copie dont le consommateur
  peut effacer le contenu.
- **`ApplicationLogger`** : port de journalisation injecté dans le coordinateur et transmis par
  callbacks aux vues et menus.
- **`ApplicationEvent`** : catalogue des événements de cycle de vie et d'interaction consignés par
  le journal applicatif.
- **`LogService`** : écrit les messages de diagnostic sur la console et dans
  `~/.gitdesk/logs/gitdesk.log`, avec rotation. Le formateur masque les identifiants inclus dans
  certaines URL et les tokens GitHub reconnus.

### Comptes GitHub/GitLab

- **`GitAccountService`** : valide un Personal Access Token en appelant le point d'API utilisateur
  GitHub ou GitLab avec `HttpClient`. Il identifie le nom de compte, restaure/vérifie une session
  sauvegardée et demande la suppression des identifiants à la déconnexion.
- **`GitAccountService.Account`** : contient le fournisseur, le nom du compte et une copie du token;
  `close()` efface le tableau de caractères.
- Le compte GitHub/GitLab sert à l'authentification API et aux opérations distantes. **L'identité
  d'auteur d'un commit est distincte** : `GitRepositoryService.commit` lit `user.name` et
  `user.email` dans la configuration Git du dépôt.

## 5. Principaux flux d'exécution

### Ouvrir et sélectionner un dépôt

1. Une action de l'utilisateur demande l'ouverture d'un dossier.
2. `GitDeskApplication.openRepository` évite les doublons et met la demande en file si une autre
   ouverture est en cours.
3. Un `Task<RepositoryOperations>` demande l'ouverture à `RepositoryServiceFactory` sur un thread
   daemon; l'implémentation par défaut délègue à JGit.
4. Au succès, l'application crée un onglet dans `RepositoryTabManager`; la sélection de cet onglet
   active son service.
5. L'application actualise l'état, l'historique et les stashs, puis démarre un `WorktreeWatcher`
   pour le dépôt actuellement sélectionné.

Chaque dépôt ouvert garde son propre `RepositoryOperations`. Le watcher automatique appartient au
dépôt actif; le changement d'onglet arrête le précédent et en démarre un pour le dépôt sélectionné.

### Affichage des changements locaux

`WorktreeWatcher` écoute les événements du système de fichiers hors du thread JavaFX. Après une
période de regroupement, son callback passe par `Platform.runLater`, puis vérifie que le dépôt
surveillé est toujours sélectionné et qu'aucune opération Git n'est déjà en cours. Il appelle alors
`GitDeskApplication.refreshRepository()`.

Le rafraîchissement demande à `GitRepositoryService` l'état staged/unstaged, la branche, les chemins
en conflit, l'historique et les stashs; la vue reçoit ces données via
`RepositoryWorkspaceView.updateRepository`. Le bouton **Refresh** reste disponible comme commande
manuelle. Le watcher est fermé lors du changement de dépôt ou à l'arrêt de l'application.

### Opération Git déclenchée depuis l'interface

Les boutons et menus appellent les interfaces `Actions` exposées par `RepositoryWorkspaceView` ou
`GitActionsMenu`. Leurs implémentations anonymes dans `GitDeskApplication` choisissent le
`repositoryService` actif et invoquent la méthode adaptée. Les commandes locales passent par
`runRepositoryAction`; les opérations distantes utilisent le flux asynchrone `runRemoteAction`.
Après l'action, l'application actualise l'état et communique le résultat dans la barre de statut et
le journal.

Les événements système et les opérations distantes peuvent demander des traitements plus longs;
les opérations asynchrones sont exécutées sous forme de `Task` JavaFX sur des threads daemon. Les
changements des contrôles JavaFX sont ensuite effectués sur le thread d'interface.

### Rebase et résolution de conflit

1. Le menu appelle `GitRepositoryService.rebase`.
2. Une fois l'opération terminée, `GitDeskApplication` rafraîchit le dépôt puis relit la liste des
   conflits : JGit peut signaler un rebase arrêté sans lancer d'exception.
3. Si des chemins conflictuels existent, l'application charge le contenu des étapes Git et ouvre
   le dialogue de résolution du premier fichier.
4. `RepositoryWorkspaceView` affiche les côtés courant et entrant, permet un choix par bloc et
   propose un résultat éditable.
5. À la validation, `GitConflictResolutionService` écrit le contenu et stage le fichier. Après le
   rafraîchissement, le prochain conflit est ouvert s'il en reste; sinon l'utilisateur peut
   continuer ou abandonner le rebase depuis les actions Git.

### Authentification

Au démarrage, `GitDeskApplication` demande à `GitAccountService` de charger puis vérifier une session
persistée. La vérification réseau et la connexion sont effectuées dans des tâches asynchrones.
Lorsqu'un compte est actif, son fournisseur d'identifiants est attaché aux services de dépôts ouverts
pour les opérations distantes. À la déconnexion, les identifiants sont retirés des services et le
secret en mémoire est effacé.

### Fermeture

`GitDeskApplication.stop()` arrête le watcher, persiste les chemins des dépôts ouverts, ferme tous
les services JGit, efface la session en mémoire et ferme le journal. La fermeture individuelle d'un
onglet ferme également le service correspondant.

## 6. Interface et ressources

La scène est construite dans le code JavaFX : `BorderPane` porte la barre d'outils et la vue active,
et `RepositoryWorkspaceView` compose l'historique, les listes et la zone de diff. L'authentification
remplace temporairement la vue centrale jusqu'à ce qu'une session soit validée. Les contrôles
portent des classes CSS JavaFX; `styles.css` les stylise au niveau de la scène et des dialogues.

Les ressources sont chargées depuis le classpath sous `com/git/client`. Aucun layout FXML n'est
utilisé : l'interface et ses liaisons sont écrites en Java; CSS ne contient que la présentation.

## 7. Principes SOLID et contrats

Le refactoring introduit des contrats aux frontières susceptibles de varier :

- `RepositoryOperations` isole le coordinateur et les vues des détails de construction du service
  JGit. `GitRepositoryService` est l'implémentation actuelle; un autre backend peut être introduit
  derrière le même contrat.
- `RepositoryServiceFactory` et `RepositoryChangeMonitorFactory` séparent la création des
  implémentations de leurs consommateurs.
- `ApplicationLogger` permet au coordinateur de signaler événements et erreurs sans coupler les
  flux métier à l'implémentation du logger.
- `RepositoryChangeMonitor` rend remplaçable l'observation NIO sans modifier les réactions de
  l'application aux changements.
- `GitCredentials` limite l'information de compte nécessaire au transport réseau.

Cela applique principalement **DIP** : le coordinateur dépend des contrats pour le dépôt, le
journal et le watcher. Le principe **ISP** motive les interfaces de création, surveillance,
journalisation et credentials séparées. L'**OCP** est amélioré aux frontières où une implémentation
alternative peut être substituée sans réécrire les consommateurs.

Les implémentations par défaut sont assemblées par injection de dépendances manuelle dans le
constructeur JavaFX sans argument, qui délègue au constructeur interne acceptant logger et factories;
aucun framework de dependency injection n'est utilisé. `RepositoryOperations` demeure assez large
car il reflète le périmètre courant de la façade; le diviser en ports de lecture, mutation locale et
transport distant est une évolution possible si de nouveaux consommateurs le justifient. Le
contrat transmet aussi `GitAPIException`, ce qui constitue un couplage JGit restant à normaliser
avant qu'un backend non-JGit puisse être branché sans adaptation. Le coordinateur garde plusieurs
responsabilités historiques : ce refactoring est une séparation ciblée des dépendances, et non une
prétention à une architecture hexagonale complète.

## 8. Design patterns présents et justification

Ce tableau décrit uniquement les patterns réellement utilisés dans le code.

| Pattern | Emplacement | Rôle et justification |
|---|---|---|
| **Façade** | `GitRepositoryService` | Cache les séquences JGit derrière des opérations de dépôt nommées (stage, commit, rebase, push, stash). Les couches UI et coordination n'ont pas à orchestrer les commandes bas niveau. |
| **Ports et adaptateurs (forme ciblée)** | `RepositoryOperations`, `ApplicationLogger`, `RepositoryChangeMonitor`; implémentations JGit, `LogService`, `WorktreeWatcher` | Les interfaces sont les ports consommés par l'application; les technologies JGit, Java Logging et NIO sont les adaptateurs actuels. Cette séparation réduit le couplage sans imposer artificiellement une architecture complète en couches. |
| **Factory** | `RepositoryServiceFactory` / `JGitRepositoryServiceFactory`, `RepositoryChangeMonitorFactory` / `NioRepositoryChangeMonitorFactory` | Encapsule la création des services concrets et évite d'éparpiller les appels statiques à JGit et `new WorktreeWatcher`. Elle permet de substituer une implémentation au point de composition. |
| **Command** | `GitActionsMenu.RepositoryAction`, `BranchAction`, `RemoteAction` | Une intention est représentée par une fonction passée au coordinateur, qui l'exécute dans le cycle commun de statut, journalisation, gestion d'erreur et rafraîchissement. Ce pattern évite une classe de commande dédiée à chaque bouton. |
| **Observer / callbacks** | propriétés JavaFX, callbacks de `RepositoryTabManager` et `RepositoryChangeMonitor` | Les changements de sélection, d'état UI et du système de fichiers sont notifiés aux consommateurs sans que le service de surveillance manipule l'interface. Le callback du watcher retourne sur le thread JavaFX via `Platform.runLater`. |
| **Registry** | `RepositoryTabManager.repositories` | L'index par chemin normalisé permet de retrouver et dédupliquer les services de dépôts ouverts. Le gestionnaire possède aussi leur cycle de vie et les ferme avec leurs onglets. |
| **Adapter** | `WindowsCredentialStore` | Adapte les opérations natives du Credential Manager et le pont PowerShell aux opérations `load`, `save` et `delete` consommées par GitPilot. Les autres classes n'accèdent pas directement aux structures Win32. |
| **Injection de dépendances manuelle** | constructeurs de `GitDeskApplication` | Le constructeur sans argument conserve la compatibilité JavaFX puis délègue au constructeur interne qui reçoit logger et factories. Cette composition simple maintient le nombre de dépendances bas; la substitution est possible au code mais n'est pas configurée par un conteneur externe. |

**Strategy n'est pas revendiqué** : GitHub et GitLab sont représentés par un enum et leurs variations sont encore traitées par des conditions dans `GitAccountService`. Une stratégie par fournisseur deviendra utile si des fournisseurs personnalisés ou davantage de comportements spécifiques sont ajoutés; l'abstraction n'est pas nécessaire actuellement.

## 9. Journalisation des événements

`ApplicationLogger` est utilisé par le coordinateur puis transmis aux vues et menus via des
callbacks. `LogService.event(ApplicationEvent, detail)` écrit une entrée normalisée `event=...`
avec les messages techniques au même emplacement. Le catalogue `ApplicationEvent` couvre le
démarrage/arrêt, l'authentification, l'ouverture/sélection/fermeture/actualisation des dépôts,
les opérations de surveillance, les recherches, la sélection d'éléments de l'historique et les
conflits. `DialogStyler` enregistre uniformément l'ouverture et la fermeture des dialogues.

Les opérations Git et réseau enregistrent début, succès et échec. Pour préserver la confidentialité,
les événements de recherche enregistrent la longueur de la requête, pas son texte; les tokens et
mots de passe ne sont jamais envoyés au journal. Les identifiants de compte, chemins et noms
d'actions sont des métadonnées opérationnelles; le formateur masque les tokens GitHub reconnus et
les identifiants incorporés dans certaines URL. Les survols et animations purement visuels sont
exclus pour garder un journal exploitable.

## 10. Tests et construction

Les tests JUnit 5 sous `src/test/java/com/git/client` couvrent notamment :

- les opérations de dépôt (stage/unstage, commit, branches distantes, rebase et stashs) dans
  `GitRepositoryServiceTest`;
- la création de services derrière `RepositoryOperations` dans `RepositoryServiceFactoryTest`;
- le découpage et l'assemblage des blocs de conflit dans `ConflictResolutionDocumentTest`;
- la détection des modifications du worktree, y compris les nouveaux répertoires, dans
  `WorktreeWatcherTest`;
- les comptes et le stockage d'identifiants dans `GitAccountServiceTest` et
  `WindowsCredentialStoreTest`.

`mvnw.cmd test` exécute la suite sur Windows; `mvnw.cmd javafx:run` lance l'application depuis
Maven. Le script `package-windows.ps1` construit les installateurs Windows (EXE et MSI) et
`PACKAGING.md` décrit le packaging.

## 11. Repères de maintenance

- Ajouter un contrôle visuel : créer/configurer le nœud JavaFX dans la vue concernée et ajouter ses
  règles dans `styles.css`.
- Ajouter une action Git : déclarer l'opération dans `RepositoryOperations`, l'implémenter dans
  `GitRepositoryService`, puis la relier au callback du menu ou de la vue.
- Remplacer le backend Git : implémenter `RepositoryOperations` et l'exposer via
  `RepositoryServiceFactory`; les vues ne doivent pas instancier JGit.
- Remplacer le watcher ou le logger : implémenter `RepositoryChangeMonitorFactory` ou
  `ApplicationLogger`, puis substituer l'implémentation à la racine de composition.
- Ajouter une lecture de diff : placer la logique dans `GitDiffHistoryService` et l'exposer si
  nécessaire par `GitRepositoryService`.
- Ajouter une ressource : la placer dans `src/main/resources/com/git/client` et la charger via le
  classpath.
- Conserver les appels JGit hors des vues : les vues émettent des intentions via callbacks; le
  coordinateur choisit le service du dépôt actif.
