# F-Droid Update Notes

This folder keeps local notes for the F-Droid submission flow.

## Current state

- App repo metadata file: `fdroid-metadata/com.nkls.nekovideo.yml`
- F-Droid fork metadata path: `metadata/com.nkls.nekovideo.yml`
- Current submitted MR: `https://gitlab.com/fdroid/fdroiddata/-/merge_requests/38725`
- Current submitted version:
  - `versionName: 1.3.1`
  - `versionCode: 23`
  - `commit: 258ca21bf807406a40a30747645163a54d89ce44`

## Before a new F-Droid update

1. Make sure the app release is already tagged in GitHub.
2. Confirm `app/build.gradle.kts` has the final `versionName` and `versionCode`.
3. Add/update upstream Fastlane changelog file:
   - `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
4. Update local metadata file:
   - `fdroid-metadata/com.nkls.nekovideo.yml`
5. Update the same metadata in the `fdroiddata` fork at:
   - `metadata/com.nkls.nekovideo.yml`

## Fields to update in the YAML

When updating an app already present in `fdroiddata`, do not replace old built entries.
Append a new build block and keep previously built blocks intact.

Update these values together:

- `Builds[n].versionName`
- `Builds[n].versionCode`
- `Builds[n].commit`
- `CurrentVersion`
- `CurrentVersionCode`

Expected build block format:

```yml
Builds:
  - versionName: 1.1.1
    versionCode: 20
    commit: e27ed54f020e11063f682bfcb833cef466f889eb
    subdir: app
    gradle:
      - yes

  - versionName: 1.3.1
    versionCode: 23
    commit: 258ca21bf807406a40a30747645163a54d89ce44
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.3.1
CurrentVersionCode: 23
```

## F-Droid fork workflow

Fork used:

- `https://gitlab.com/FellipitoPV/fdroiddata`

Recommended flow:

1. Sync fork with upstream `fdroid/fdroiddata`.
2. Create a branch in the fork.
3. Edit `metadata/com.nkls.nekovideo.yml`.
   Add a new build block, do not replace already built ones.
4. Commit and push branch.
5. Open/update MR to `fdroid/fdroiddata`.

## Useful commands

Check GitLab auth:

```powershell
glab auth status
```

Clone personal fork:

```powershell
git clone https://gitlab.com/FellipitoPV/fdroiddata.git
```

Sync fork clone with upstream:

```powershell
git remote add upstream https://gitlab.com/fdroid/fdroiddata.git
git fetch upstream
git checkout master
git merge --ff-only upstream/master
```

Create branch, commit, and push:

```powershell
git checkout -b update-nekovideo-x-y-z
git add metadata/com.nkls.nekovideo.yml
git commit -m "Update NekoVideo to x.y.z"
git push -u origin update-nekovideo-x-y-z
```

Create MR with `glab`:

```powershell
glab mr create --source-branch update-nekovideo-x-y-z --target-branch master --title "Update NekoVideo to x.y.z"
```

## Important notes

- The metadata file in your `fdroiddata` fork already existed upstream and was outdated before this update.
- The local `fdroid-metadata/` folder is only a reference/work area; F-Droid uses the file inside the `fdroiddata` fork.
- Keep changelog entries short and plain.
- Do not forget to create the GitHub tag before updating F-Droid metadata.
- Prefer using the exact commit hash in `commit:` instead of the tag name.
