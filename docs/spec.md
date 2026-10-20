# Cardvault: data format and sync contract (v1)

This document is the contract for the Android app and the later iOS app. Both platforms must give the same result for the same input.

Terms:
- **Local**: the contacts that the app keeps on the device.
- **Remote**: the contacts file in the user's Proton Drive.
- **Base**: the last remote content that this device synced. The base is the common ancestor for a three-way merge.
- **Phone store**: the operating system contacts database (Android `ContactsContract`).
- **Mirror base**: the contacts as they were when the app last synced the local contacts with the phone store.

## 1. Drive layout

- Folder: `My files/Cardvault`.
- File: `My files/Cardvault/contacts.vcf`, media type `text/vcard`.
- One file holds all contacts. Each change uploads a new revision of this file.
- The app finds the folder and the file by name. It lists only the direct children of `My files` and of `Cardvault`. It never walks the tree recursively.

## 2. File format

### 2.1 Output (canonical serialization)

The app writes vCard 4.0 (RFC 6350):
- UTF-8, CRLF line ends, a final CRLF after the last `END:VCARD`.
- Lines are folded at 75 octets. A continuation line starts with one space. The writer never splits a UTF-8 multi-byte sequence.
- Cards are sorted by UID (ordinal string comparison).
- Properties are written in this order. Empty properties are not written.

```
BEGIN:VCARD
VERSION:4.0
UID:<uid>
REV:<yyyyMMdd'T'HHmmss'Z'>
FN:<formattedName>
N:<family>;<given>;<additional>;<prefix>;<suffix>
NICKNAME:<nickname>
ORG:<organization>[;<department>]
TITLE:<title>
BDAY:<birthday>
TEL;TYPE=<type>:<value>                (one line per phone, in list order)
EMAIL;TYPE=<type>:<value>              (one line per email, in list order)
ADR;TYPE=<type>:<poBox>;<extended>;<street>;<locality>;<region>;<postalCode>;<country>
URL:<value>                            (one line per URL)
NOTE:<note>
<extra lines, verbatim, in list order>
END:VCARD
```

- `N` is written when at least one component is not empty.
- `ORG` is written when the organization or the department is not empty. The `;<department>` part is written only when the department is not empty.
- Text escaping in values: `\` → `\\`, newline → `\n`, `,` → `\,`, `;` → `\;`. Structured values (`N`, `ORG`, `ADR`) escape each component and join them with a literal `;`.
- A typed value with a custom label uses an Apple/Google group. The writer numbers the groups `cv1`, `cv2`, … per card, in output order:
  ```
  cv1.TEL:+1 555 0100
  cv1.X-ABLabel:Boat
  ```
- Two serializations of equal contact lists give the same bytes. The sync engine compares bytes to decide if an upload is necessary.

### 2.2 Type mapping

| Kind | Applies to | Written as | Read from (case-insensitive) |
| --- | --- | --- | --- |
| `MOBILE` | TEL, EMAIL | `TYPE=cell` (TEL), `TYPE=x-mobile` (EMAIL) | `CELL`, `MOBILE`, `X-MOBILE` |
| `HOME` | TEL, EMAIL, ADR | `TYPE=home` | `HOME` without `FAX` |
| `WORK` | TEL, EMAIL, ADR | `TYPE=work` | `WORK` without `FAX` |
| `MAIN` | TEL | `TYPE=main` | `MAIN`, `X-MAIN`, `PREF` alone |
| `FAX_HOME` | TEL | `TYPE=home,fax` | `FAX` with `HOME` |
| `FAX_WORK` | TEL | `TYPE=work,fax` | `FAX` with `WORK`, or `FAX` alone |
| `FAX_OTHER` | TEL | `TYPE=fax,x-other` | `FAX` with `OTHER` |
| `PAGER` | TEL | `TYPE=pager` | `PAGER` |
| `OTHER` | TEL, EMAIL, ADR | `TYPE=x-other` | `OTHER`, `X-OTHER`, `VOICE` alone, `INTERNET` alone, no type |
| `CUSTOM` | TEL, EMAIL, ADR | group with `X-ABLabel` (see 2.1) | `X-ABLabel` in the same group; `TYPE=X-<label>` is also a custom label `<label>` |

Rules for reading:
- `PREF` and `pref=<n>` do not change the kind, except that `PREF` alone on TEL gives `MAIN`.
- `X-ABLabel` values of the form `_$!<Mobile>!$_` (Apple) map to the standard kinds: `Mobile`→`MOBILE`, `Home`→`HOME`, `Work`→`WORK`, `Main`→`MAIN`, `HomeFAX`→`FAX_HOME`, `WorkFAX`→`FAX_WORK`, `OtherFAX`→`FAX_OTHER`, `Pager`→`PAGER`, `Other`→`OTHER`. Other `_$!<x>!$_` values give `CUSTOM` with label `x`.
- A TEL value in URI form (`tel:+1555…`) loses the `tel:` prefix.

### 2.3 Input (lenient parser)

The parser reads vCard 2.1, 3.0 and 4.0. This covers Google Contacts exports (3.0), Android exports (2.1) and Apple exports (3.0).
- It accepts CRLF, LF and CR line ends.
- It unfolds lines that start with a space or a tab.
- It accepts vCard 2.1 quoted-printable values (`ENCODING=QUOTED-PRINTABLE`, `QUOTED-PRINTABLE` bare parameter), including soft line breaks (`=` at the end of the line) and `CHARSET=`. It decodes the bytes as the given charset (default UTF-8).
- It accepts vCard 2.1 bare type parameters (`TEL;CELL;PREF:`).
- Property names and parameter names are case-insensitive. Parameter values can be quoted. A `:`, `;` or `,` inside quotes is literal.
- It removes the group prefix (`item1.`) before it matches a property name. It uses the group only to join `X-ABLabel` to its property.
- `NICKNAME` with several values: the values are joined with `, `.
- `ORG`: component 1 is the organization, component 2 is the department. Other components are dropped.
- `BDAY`: `YYYYMMDD` → `YYYY-MM-DD`; `--MMDD` → `--MM-DD`; `YYYY-MM-DD` and `--MM-DD` stay. A date-time keeps only the date part. Other text stays unchanged.
- `REV`: the parser accepts `yyyyMMdd'T'HHmmss'Z'`, ISO-8601 instants and `yyyy-MM-dd'T'HH:mm:ss'Z'`. A value it cannot read gives epoch 0.
- `PHOTO`, `LOGO`, `SOUND`, `KEY` with binary or `data:` content are dropped. The parser reports the number of dropped properties.
- `PRODID`, `VERSION`, `BEGIN`, `END` are not stored.
- All other properties (for example `IMPP`, `RELATED`, `ANNIVERSARY`, `GENDER`, `CATEGORIES`, `X-*`) are kept as extras: the unfolded line, verbatim, with its group prefix. A grouped `X-ABLabel` whose group has a known property is not an extra.
- `FN` missing: the parser makes it from `N` (`prefix given additional family suffix`, single spaces, trimmed), then from the organization, then from the first email, then from the first phone. If all are empty, `FN` is empty.
- `UID` missing: the parser makes `urn:uuid:<random UUID v4, lowercase>`.
- Duplicate UID in one file: the first card keeps the UID. Each later card gets a new random UID.
- A card without `END:VCARD` at the end of the file is still read.

## 3. Contact model

```
Contact
  uid: String
  rev: Instant                  (not part of content equality)
  formattedName: String
  name: StructuredName(family, given, additional, prefix, suffix: String)   (all "" when absent)
  nickname: String
  organization: Organization(name, department: String)
  title: String
  birthday: String
  phones: List<TypedValue>
  emails: List<TypedValue>
  addresses: List<PostalAddress>
  urls: List<String>
  note: String
  extras: List<String>

TypedValue(kind: Kind, customLabel: String, value: String)        (customLabel is "" unless kind = CUSTOM)
PostalAddress(kind: Kind, customLabel: String, poBox, extended, street, locality, region, postalCode, country: String)
```

- Absent text is the empty string, never null.
- **Content equality** compares all fields except `rev`.

## 4. Three-way merge

`merge3(base, local, remote) → MergeResult(contacts, conflicts)`. The inputs are lists of contacts. The function keys them by UID.

### 4.1 Presence

For each UID in the union of the three inputs. `b`, `l`, `r` are the contact in base, local and remote, or absent:

| b | l | r | Result | Conflict |
| --- | --- | --- | --- | --- |
| any | absent | absent | absent | none |
| absent | present | absent | `l` | none |
| absent | absent | present | `r` | none |
| present | absent | present, `r` equals `b` | absent | none |
| present | absent | present, `r` differs from `b` | `r` | `DELETED_LOCALLY` |
| present | present, `l` equals `b` | absent | absent | none |
| present | present, `l` differs from `b` | absent | `l` | `DELETED_REMOTELY` |
| any | present | present | field merge (4.2) with base `b`, or with an empty contact when `b` is absent | `FIELDS` if a field conflicts |

The merge never deletes a contact that one side changed. It keeps the changed copy and reports a conflict.

### 4.2 Field merge

Scalar fields: `formattedName`, `name` (one value), `nickname`, `organization` (one value), `title`, `birthday`, `note`.
- `l == b` → take `r`.
- else `r == b` → take `l`.
- else `l == r` → take `l`.
- else → take `r` as the provisional value, and add a field conflict `(field, base, local, remote)`.

List fields: `phones`, `emails`, `addresses`, `urls`, `extras`. Use multiset operations on whole items (an item is equal only when all of its parts are equal):
- `removedLocally = b − l`
- `addedLocally = l − b`
- `result = (r − removedLocally) ++ (addedLocally − r)`
- Order: the remote order first, then the local additions in local order.
- List fields never conflict. Concurrent edits of one phone number give both numbers.

`rev` of the result: `r.rev` when the result has the content of `r`; else `l.rev` when it has the content of `l`; else the larger of `l.rev` and `r.rev`.

### 4.3 Conflicts

```
Conflict
  uid: String
  kind: FIELDS | DELETED_LOCALLY | DELETED_REMOTELY
  base: Contact?        (absent when the contact was added on both sides)
  local: Contact?       (absent for DELETED_LOCALLY)
  remote: Contact?      (absent for DELETED_REMOTELY)
  fields: List<Field>   (the conflicting scalar fields; empty unless kind = FIELDS)
```

`Field` is one of `FORMATTED_NAME, NAME, NICKNAME, ORGANIZATION, TITLE, BIRTHDAY, NOTE`.

The merged result contains the provisional values. The app uploads the provisional result, so that other devices see one consistent file. The local values stay in the conflict record until the user resolves the conflict.

Resolution:
- `FIELDS`: for each field, the user picks the local or the remote value. The app writes the result as a local edit. The next sync uploads it.
- `DELETED_LOCALLY`: the user picks "Delete" (local edit: remove the contact) or "Keep" (no change).
- `DELETED_REMOTELY`: the user picks "Delete" or "Keep" (no change).
- A new conflict for the same UID replaces the old one. A conflict for a contact that no longer exists is removed.

### 4.4 Diff for the side-by-side view

`diff(left: List<Contact>, right: List<Contact>) → List<ContactDiff>`:
- `ContactDiff(uid, displayName, status: ADDED | REMOVED | CHANGED, rows: List<DiffRow>)`. Unchanged contacts are not in the list. `ADDED` means only in `right`, `REMOVED` means only in `left`.
- `DiffRow(label, left: List<String>, right: List<String>, changed: Boolean)`. There is one row per model field, in model order. A list field gives one display string per item (for example `Mobile: +1 555 0100`). An absent side gives an empty list.
- The list is sorted by display name (case-insensitive), then by UID.

## 5. Drive sync algorithm

State, saved after each step that changes it: `folderUid`, `fileUid`, `scopeId`, `eventCursor`, `baseRevisionUid`, the base bytes, `lastSyncAt`, `lastError`.

One sync at a time per device (process mutex and a unique WorkManager job).

1. **Find or create the remote file** when `fileUid` is unknown:
   - `root = getMyFilesFolder()`; `scopeId = root.treeEventScopeId`.
   - Find a folder `Cardvault` (not trashed) among the children of `root`. If there is none, create it. If the create gives a name conflict, list again and use the existing folder.
   - Find a file `contacts.vcf` (not trashed) in the folder.
     - Found: set `fileUid`. `baseRevisionUid` stays empty, so step 3 downloads it. Base content is empty, so the first merge is a two-way merge.
     - Not found: upload the local content as a new file. A name conflict gives the existing node: use it as "found". On success: `base = uploaded bytes`, `baseRevisionUid = result.revisionUid`.
   - If `eventCursor` is empty, get the latest cursor: `enumerateEvents(scopeId, null)` gives one `CursorAdvanced` event.
2. **Read events** from `eventCursor`. The remote is "maybe changed" when an event names `fileUid` or `folderUid`, or on `ContinuityLost`, or when `baseRevisionUid` is empty. Save the id of the last event as the new cursor only after step 3 completes.
3. **Read the remote** when it is "maybe changed": `getNode(fileUid)`.
   - Absent or trashed: forget `fileUid` and `baseRevisionUid`, and go to step 1. The local contacts are kept and uploaded again.
   - `activeRevision.uid == baseRevisionUid`: no remote change.
   - Else: download that revision, parse it, `merge3(base, localSnapshot, remote)`. Write the merged contacts to local. If the local contacts changed while the download ran, merge again: `merge3(localSnapshot, localNow, merged)`. Save the conflicts. Set `base = downloaded bytes`, `baseRevisionUid = activeRevision.uid`.
4. **Upload** when `serialize(local) != base bytes`: upload a new revision with `currentActiveRevisionUid = baseRevisionUid`. The Drive API rejects the revision when the given revision is no longer the active one (HTTP 409).
   - On success: `base = uploaded bytes`, `baseRevisionUid = result.revisionUid`.
   - On failure: `getNode(fileUid)`. If the active revision is not `baseRevisionUid`, another device uploaded first: go to step 3 (at most 3 times per sync). Else report the error.
5. **Phone mirror** (section 6) runs before step 1 and again after step 3 when step 3 changed the local contacts.

Triggers:
- App in the foreground: every 30 seconds, and 3 seconds after the last local edit.
- Background: a periodic WorkManager job every 15 minutes, with a network constraint.
- Android sync adapter: `onPerformSync` (for example after an edit in the phone contacts app).
- Failures use exponential backoff (WorkManager backoff, 30 s start).

The app never polls `getNode` without an event, except in step 1 and in the retry of step 4.

## 6. Phone contacts mirror (Android)

- Account type `dev.cardvault.account`, account name = the user's primary email address.
- Each raw contact of this account has `SOURCE_ID = uid`.
- `mirror(local)`:
  1. Read all raw contacts of the account. A raw contact with `DELETED = 1` is a deletion. A raw contact without `SOURCE_ID` is new: it gets a new UID.
  2. For each phone contact, copy `extras` from the mirror base (the phone store cannot keep them).
  3. `merge3(mirrorBase, local, phone)`. Field conflicts take the phone value (the provisional value) and are not shown to the user.
  4. Write the result to local when it changed.
  5. Write the result to the phone store with `CALLER_IS_SYNCADAPTER = true`: insert, update (replace all data rows of the raw contact) or delete raw contacts. Purge the deleted raw contacts.
  6. `mirrorBase = result`.
- Data row mapping:

| Model | ContactsContract |
| --- | --- |
| `formattedName`, `name` | `StructuredName`: `DISPLAY_NAME`, `FAMILY_NAME`, `GIVEN_NAME`, `MIDDLE_NAME`, `PREFIX`, `SUFFIX` |
| `nickname` | `Nickname.NAME` (first row) |
| `organization`, `title` | `Organization`: `COMPANY`, `DEPARTMENT`, `TITLE` (first row) |
| `phones` | `Phone`: `NUMBER`, `TYPE`, `LABEL` |
| `emails` | `Email`: `ADDRESS`, `TYPE`, `LABEL` |
| `addresses` | `StructuredPostal`: `POBOX`, `NEIGHBORHOOD` (= extended), `STREET`, `CITY`, `REGION`, `POSTCODE`, `COUNTRY`, `TYPE`, `LABEL` |
| `urls` | `Website.URL`, `TYPE_OTHER` |
| `birthday` | `Event` with `TYPE_BIRTHDAY`, `START_DATE` |
| `note` | `Note.NOTE` |

- Kind mapping for `Phone.TYPE`: `MOBILE`=`TYPE_MOBILE`, `HOME`=`TYPE_HOME`, `WORK`=`TYPE_WORK`, `MAIN`=`TYPE_MAIN`, `FAX_HOME`=`TYPE_FAX_HOME`, `FAX_WORK`=`TYPE_FAX_WORK`, `FAX_OTHER`=`TYPE_OTHER_FAX`, `PAGER`=`TYPE_PAGER`, `OTHER`=`TYPE_OTHER`, `CUSTOM`=`TYPE_CUSTOM` + `LABEL`. Other Android phone types read as `CUSTOM` with the Android type label.
- `Email.TYPE` and `StructuredPostal.TYPE`: `HOME`, `WORK`, `OTHER`, `MOBILE` (email only), `CUSTOM` + `LABEL`.

## 7. Import

Sources:
- **Device accounts**: raw contacts of other accounts in `ContactsContract`, grouped by account (`ACCOUNT_TYPE`, `ACCOUNT_NAME`). This includes Google accounts (`com.google`) and the local device account. The user selects the accounts.
- **vCard file**: a file that the user picks with the Storage Access Framework (for example a Google Contacts export from contacts.google.com).

Rules:
- Each imported contact gets a new UID.
- Duplicate check against the local contacts: two contacts are duplicates when their normalized formatted names are equal (trimmed, lowercase, single spaces) and one of these is true:
  - they share a phone number (compare the last 9 digits of the digits-only value; shorter values compare all digits),
  - they share an email address (trimmed, lowercase),
  - both have no phones and no emails.
- Duplicates are skipped. The import reports the counts: imported, skipped as duplicate, dropped properties.
- The import is a local edit. The next sync uploads it.

## 8. Local storage (Android, app files directory)

| Path | Content |
| --- | --- |
| `contacts/local.vcf` | Local contacts, canonical serialization |
| `contacts/base.vcf` | Base bytes (exact remote bytes) |
| `contacts/mirror-base.vcf` | Mirror base |
| `contacts/conflicts.json` | Open conflicts |
| `contacts/conflict-local.vcf`, `contacts/conflict-remote.vcf` | The two files of the last merge that gave conflicts (for the side-by-side file view) |
| `sync-state.json` | Drive sync state (section 5) |
| `session.bin` | Session secrets, AES-GCM with an Android Keystore key |
| `drive-cache.sqlite` | Drive SDK cache, encrypted with `cacheEncryptionKey` |

All writes are atomic (write a temporary file, then rename).

## 9. Sign-in and API use

- Sign-in uses the Proton session fork (the same flow as the Drive SDK CLI):
  1. `GET https://drive-api.proton.me/auth/v4/sessions/forks` → `Selector`, `UserCode`.
  2. The app makes a random 32-byte key and opens `https://account.proton.me/desktop/login?app=drive&pv=3#payload=<urlencode("0:" + UserCode + ":" + base64(key) + ":external-drive")>` in a browser tab.
  3. The app polls `GET /auth/v4/sessions/forks/{Selector}` every 5 seconds, for at most 10 minutes. HTTP 422 means "not ready".
  4. The response gives `UID`, `AccessToken`, `RefreshToken`, `UserID`, `Payload`. `Payload` is base64 of `nonce(12) || ciphertext || tag(16)`, AES-256-GCM, AAD `fork`. The plaintext is JSON with `keyPassword`.
- `keyPassword` unlocks the user keys. Address keys with `Token` and `Signature`: decrypt `Token` with the user keys, verify `Signature` (detached) with the user keys, and use the plaintext as the address key passphrase. Address keys without `Token`: use `keyPassword`.
- The Drive SDK gets the unlocked address keys as binary (serialized) OpenPGP keys.
- Every request has `x-pm-appversion: external-drive-cardvault@<version>-<channel>`. Authenticated requests also have `x-pm-uid` and `Authorization: Bearer <AccessToken>`.
- Token refresh: `POST /auth/v4/refresh` with `{"ResponseType":"token","GrantType":"refresh_token","RefreshToken":…,"RedirectURI":"https://proton.me"}`. Only one refresh runs at a time. A 4xx answer other than 429 signs the user out.
- The sign-in screen shows: "This is a third-party application not officially supported by Proton."
- The app does not use Proton logos or trademarks in its name or icon.
