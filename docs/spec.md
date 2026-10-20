# DriveContacts: data format and sync contract (v1)

This document is the contract for every DriveContacts client. All clients must give the same result for the same input.

Terms:
- **Local**: the contacts that the app keeps on the device.
- **Remote**: the contacts file in the user's Proton Drive.
- **Base**: the last remote content that this device synced. The base is the common ancestor for a three-way merge.
- **Phone store**: the operating system contacts database (Android `ContactsContract`).
- **Mirror base**: the contacts as they were when the app last synced the local contacts with the phone store.

## 1. Drive layout

- Folder: `My files/DriveContacts`.
- File: `My files/DriveContacts/contacts.vcf`, media type `text/vcard`.
- One file holds all contacts. Each change uploads a new revision of this file.
- The app finds the folder and the file by name. It lists only the direct children of `My files` and of `DriveContacts`. It never walks the tree recursively.

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
- The writer trims the UID, custom labels and the nickname. It writes the birthday in the form that the parser gives (`BDAY` rule in 2.3). A custom label that is empty after trimming gives no group; the value is written with `TYPE=x-other`.
- Extra lines contain no `CHARSET` and no `QUOTED-PRINTABLE` encoding (see 2.3).
- A typed value with a custom label uses an Apple/Google group. The writer numbers the groups `cv1`, `cv2`, … per card, in output order:
  ```
  cv1.TEL:+1 555 0100
  cv1.X-ABLabel:Boat
  ```
- Two serializations of equal contact lists give the same bytes. The sync engine compares bytes to decide if an upload is necessary.
- `canonical(contact)` is the contact that the parser reads from the contact's own serialization, with the same `rev`. `parse(serialize([canonical(c)]))` has the same content as `canonical(c)`.

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
- Version: the version of a card is the value of the last `VERSION` line before the current line in the same card. A card starts without a version. The app's own writer always writes `VERSION` right after `BEGIN`. Lines before the `VERSION` line are read as not 4.0.
- Input encoding: a UTF-16 LE or BE byte order mark selects that encoding, and a UTF-8 byte order mark is skipped. Otherwise the parser splits lines on the bytes and decodes each value with its `CHARSET` parameter (for example ISO-8859-1 or Shift_JIS). Without the parameter, or with an unknown name, it uses UTF-8. A plain (not quoted-printable) value is always UTF-8 when the card version is 4.0 and for UTF-16 input. A quoted-printable value uses its `CHARSET` in every case, because old files of this app hold such lines in 4.0 cards.
- Nested cards (vCard 2.1): a `BEGIN:VCARD` right after an `AGENT` property line starts a nested card. An `AGENT` property whose value is `BEGIN:VCARD` (ignoring case and surrounding white space) starts a nested card too. The parser drops the `AGENT` line and all lines up to the matching `END:VCARD`, and it keeps the properties of the outer card that follow. Nested cards can nest further. Any other `BEGIN:VCARD` before an `END:VCARD` ends the card in progress and starts a new card.
- It unfolds lines that start with a space or a tab.
- It accepts vCard 2.1 quoted-printable values (`ENCODING=QUOTED-PRINTABLE`, or the bare parameter `QUOTED-PRINTABLE`), including soft line breaks (`=` at the end of a line). A soft break does not continue into an empty line or into a `BEGIN:VCARD` or `END:VCARD` line (any case). In a card whose version is 4.0 (see "Version" above), a line that ends in `=` never opens a soft break, because vCard 4.0 has no quoted-printable; a line that starts with a space or a tab after it is a normal fold. The parser decodes the bytes with the `CHARSET` parameter (default UTF-8). It decodes quoted-printable values of extras too. Extra lines keep neither `CHARSET` nor the quoted-printable encoding. A new line in a decoded extra is written as `\n`.
- It accepts vCard 2.1 bare type parameters (`TEL;CELL;PREF:`).
- Property names and parameter names are case-insensitive. Parameter values can be quoted. A `:`, `;` or `,` inside quotes is literal.
- It removes the group prefix (`item1.`) before it matches a property name. It uses the group only to join `X-ABLabel` to its property.
- `NICKNAME` with several values: the values are joined with `, `.
- `ORG`: component 1 is the organization, component 2 is the department. Other components are dropped.
- `BDAY`: `YYYYMMDD` → `YYYY-MM-DD`; `--MMDD` → `--MM-DD`; `YYYY-MM-DD` and `--MM-DD` stay. A date-time keeps only the date part. Other text stays unchanged.
- `REV`: the parser accepts `yyyyMMdd'T'HHmmss'Z'`, ISO-8601 instants and `yyyy-MM-dd'T'HH:mm:ss'Z'`. A value it cannot read gives epoch 0.
- `PHOTO`, `LOGO`, `SOUND`, `KEY` are dropped unless the value is a URI that is not a `data:` URI and has no `ENCODING` parameter. The parser counts the dropped properties and does not keep their values.
- `PRODID`, `VERSION`, `BEGIN`, `END` are not stored.
- All other properties (for example `IMPP`, `RELATED`, `ANNIVERSARY`, `GENDER`, `CATEGORIES`, `X-*`) are kept as extras: the unfolded line with its group prefix, verbatim except that quoted-printable values are decoded and `CHARSET` and the quoted-printable encoding are removed (see above). A grouped `X-ABLabel` whose group has a known property is not an extra.
- `FN` missing: the parser makes it from `N` (`prefix given additional family suffix`, single spaces, trimmed), then from the organization, then from the first email, then from the first phone. If all are empty, `FN` is empty.
- `UID` missing: the parser derives the UID from the card, as follows.
  - **Logical lines.** A logical line is a property line after unfolding. Fold: a line break (CRLF, LF or CR) followed by a space or a tab is removed together with that one space or tab. Soft break: in a quoted-printable line (not in a 4.0 card), a trailing `=` and the line break after it are removed. The bytes are as in the file, or UTF-8 for UTF-16 input.
  - **Hash `H`.** `H` is the SHA-256 of the concatenation, for each logical line of the card in file order, of the line bytes followed by one LF byte. These lines are included: all property lines of the card (`VERSION` and `PRODID` too), all lines of nested cards with their `BEGIN:VCARD`/`END:VCARD` lines, and the `AGENT` line that opens a nested card.
  - **Excluded from `H`.** The `BEGIN:VCARD` and `END:VCARD` lines of the card itself; empty lines; lines without a colon outside quotes; lines with an empty property name; text outside the card.
  - **Binary lines.** For a dropped `PHOTO`, `LOGO`, `SOUND` or `KEY` line, only the bytes before its first colon outside quotes (the head) are hashed, plus LF.
  - **UID.** For occurrence number `n` (counted from 0, per `H`) the UID is `urn:uuid:` plus a UUID built from the first 16 bytes of SHA-256(`H` ‖ `n` as 4 bytes big-endian). The version nibble is 5 and the variant bits are `10`, in lowercase hex. Identical cards without UID get `n` = 0, 1, 2, …
- Duplicate UID in one file: the first card keeps the UID. Each later card gets a derived UID as above, with `H` of its own lines. If a derived UID is already used, the next `n` is tried.
- Parsing is a pure function: the same bytes always give the same contacts and UIDs.
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

### 4.5 Edits in the app

Saving an edit runs `merge3(contact when the form opened, current contact, form)` under the repository lock, so a change that a sync brought in during the edit stays. The form wins on a field that both sides changed.

## 5. Drive sync algorithm

State, saved after each step that changes it: `folderUid`, `fileUid`, `scopeId`, `eventCursor`, `baseRevisionUid`, the base bytes, `lastSyncAt`, `lastError`.

One sync at a time per device (process mutex and a unique WorkManager job).

Before each sync the app checks, under the sync lock, that the local data belongs to the signed-in user. If not, it first deletes the local data, the phone mirror and the Drive cache. Sign-out cancels a running sync and always completes its wipe.

1. **Find or create the remote file** when `fileUid` is unknown:
   - `root = getMyFilesFolder()`; `scopeId = root.treeEventScopeId`.
   - Find a folder `DriveContacts` (not trashed) among the children of `root`. If there is none, create it. If the create gives a name conflict, list again and use the existing folder.
   - Find a file `contacts.vcf` (not trashed) in the folder.
     - Found: set `fileUid`. `baseRevisionUid` stays empty, so step 3 downloads it. Base content is empty, so the first merge is a two-way merge.
     - Not found: upload the local content as a new file. A name conflict with an unfinished upload of another client: create again and replace that upload. A name conflict with a file: use that file as "found". On success: `base = uploaded bytes`, `baseRevisionUid = result.revisionUid`.
   - If `eventCursor` is empty, get the latest cursor: `enumerateEvents(scopeId, null)` gives one `CursorAdvanced` event.
2. **Read events** from `eventCursor`. The remote is "maybe changed" when an event names `fileUid` or `folderUid`, or on `ContinuityLost`, or when `baseRevisionUid` is empty. Save the id of the last event as the new cursor only after step 3 completes.
3. **Read the remote** when it is "maybe changed": `getNode(fileUid)`.
   - Absent or trashed: forget `fileUid` and `baseRevisionUid`, and go to step 1. The local contacts are kept and uploaded again.
   - `activeRevision.uid == baseRevisionUid`: no remote change.
   - Else: download that revision, parse it, `merge3(base, localSnapshot, remote)`. Write the merged contacts to local. If the local contacts changed while the download ran, merge again: `merge3(localSnapshot, localNow, merged)`. Save the conflicts with the two inputs of the last merge as the conflict files. Set `base = downloaded bytes`, `baseRevisionUid = activeRevision.uid`.
4. **Upload** when `serialize(local) != base bytes`: upload a new revision with `currentActiveRevisionUid = baseRevisionUid`. The Drive API rejects the revision when the given revision is no longer the active one (HTTP 409).
   - On success: `base = uploaded bytes`, `baseRevisionUid = result.revisionUid`.
   - On failure: `getNode(fileUid)`. If the active revision is not `baseRevisionUid`, another device uploaded first: go to step 3 (at most 3 times per sync). Else report the error.
5. **Phone mirror** (section 6) runs before step 1 and again after step 3 when step 3 changed the local contacts.

Triggers:
- App in the foreground: every 30 seconds, and 3 seconds after the last local edit.
- A request during a running sync queues one more run. A request never cancels a running sync.
- Background: a periodic WorkManager job every 15 minutes, with a network constraint, scheduled at sign-in.
- Android sync adapter: `onPerformSync` (for example after an edit in the phone contacts app).
- Network, transport and HTTP 429/5xx failures retry with exponential backoff (WorkManager backoff, 30 s start). Other failures wait for the next trigger. Every failure goes to `lastError`.

The app never polls `getNode` without an event, except in step 1 and in the retry of step 4.

## 6. Phone contacts mirror (Android)

- Account type `dev.drivecontacts.account`, account name = the user's primary email address. The account holds a random token (account user data) that identifies this instance of the account.
- The account type declares an edit schema (`android.provider.CONTACTS_STRUCTURE`) with the mapped kinds only.
- Each raw contact of this account has `SOURCE_ID = uid`.
- `mirror(local)`:
  1. Read the raw contacts of the account. A raw contact with `DELETED = 1` is a deletion. The read fails when the account is missing or the raw contacts change during the read.
  2. Give each raw contact that has content but no `SOURCE_ID` a new UID. Write these `SOURCE_ID` values to the phone store, then read again.
  3. The mirror base is empty when its token is not the account token, or when no base contact has a raw contact in the account (the phone store was cleared). A base without a token (older versions) takes the current token.
  4. Make each phone contact canonical (2.1). Copy `extras` from the mirror base (the phone store cannot keep them).
  5. `merge3(mirrorBase, local, phone)`. Field conflicts take the phone value (the provisional value) and are not shown to the user.
  6. Write the result to local when it changed.
  7. Write the result to the phone store with `CALLER_IS_SYNCADAPTER = true`: insert, update or delete raw contacts, and purge the deleted raw contacts. An update changes only the mapped data rows (the first row of a single field; all rows of the kind when the field is empty) and keeps `IS_PRIMARY`/`IS_SUPER_PRIMARY` on unchanged values. Each change first checks that the `VERSION` of the raw contact is unchanged since the read. If not, the pass fails and the next pass repeats it.
  8. Save the mirror base when it changed, then its token.
- A pass is skipped when local and the raw contacts (ids, versions, `DIRTY`, `DELETED`) are unchanged since the last pass that wrote nothing.
- A failed pass goes to `lastError`. The Drive sync continues.
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
| `contacts/mirror-base.account` | Token of the phone account that the mirror base belongs to |
| `contacts/conflicts.json` | Open conflicts |
| `contacts/conflict-local.vcf`, `contacts/conflict-remote.vcf` | The two files of the last merge that gave conflicts (for the side-by-side file view) |
| `sync-state.json` | Drive sync state (section 5) |
| `session.bin` | Session secrets, AES-GCM with an Android Keystore key |
| `drive-cache.sqlite` | Drive SDK cache, encrypted with `cacheEncryptionKey` |

All writes are atomic (write a temporary file, then rename). The app deletes `session.bin` only when the Keystore rejects its bytes, or when the user taps "Try again" after a second failed read.

## 9. Sign-in and API use

- Sign-in uses the Proton session fork (the same flow as the Drive SDK CLI):
  1. `GET https://drive-api.proton.me/auth/v4/sessions/forks` → `Selector`, `UserCode`.
  2. The app makes a random 32-byte key and opens `https://account.proton.me/desktop/login?app=drive&pv=3#payload=<urlencode("0:" + UserCode + ":" + base64(key) + ":external-drive")>` in a browser tab.
  3. The app polls `GET /auth/v4/sessions/forks/{Selector}` every 5 seconds, for at most 10 minutes. HTTP 422 means "not ready".
  4. The response gives `UID`, `AccessToken`, `RefreshToken`, `UserID`, `Payload`. `Payload` is base64 of `nonce(12) || ciphertext || tag(16)`, AES-256-GCM, AAD `fork`. The plaintext is JSON with `keyPassword`.
- `keyPassword` unlocks the user keys. Address keys with `Token` and `Signature`: decrypt `Token` with the user keys, verify `Signature` (detached) with the user keys, and use the plaintext as the address key passphrase. Address keys without `Token`: use `keyPassword`.
- The Drive SDK gets the unlocked address keys as binary (serialized) OpenPGP keys.
- Every request has `x-pm-appversion: external-drive-drivecontacts@<version>-<channel>`. Authenticated requests also have `x-pm-uid` and `Authorization: Bearer <AccessToken>`.
- Token refresh: `POST /auth/v4/refresh` with `{"ResponseType":"token","GrantType":"refresh_token","RefreshToken":…,"RedirectURI":"https://proton.me"}`. Only one refresh runs at a time. The refresh request and the save of its tokens cannot be cancelled. HTTP 400, 401 and 422 sign the user out. Other answers keep the session.
- The account API client pins the same TLS keys as ProtonCore.
- A sign-in that fails or is cancelled revokes its session.
- Unlocked keys are cached for at most 1 hour. When the keys no longer unlock (for example after a password change), the user is signed out.
- Branding (Proton Drive SDK guidelines): the sign-in screen and the About dialog say "This is a third-party application not officially supported by Proton." The app uses no Proton logos, trademarks or design elements.
