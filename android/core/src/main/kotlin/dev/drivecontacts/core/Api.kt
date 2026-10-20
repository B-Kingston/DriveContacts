package dev.drivecontacts.core

import dev.drivecontacts.core.model.Contact
import kotlinx.serialization.Serializable

// Public contract of the core module. docs/spec.md defines the behavior.
// The function bodies are owned by the core module implementation (sections 2, 4, 4.4, 7 of the spec).

/** Result of [VCard.parse]. */
data class ParseResult(
    val contacts: List<Contact>,
    /** Number of PHOTO/LOGO/SOUND/KEY properties that the parser dropped. */
    val droppedProperties: Int,
)

object VCard {
    /** Lenient parser for vCard 2.1, 3.0 and 4.0 (spec 2.3). */
    fun parse(bytes: ByteArray): ParseResult = VCardParser.parse(bytes)

    /** Canonical vCard 4.0 serialization (spec 2.1). Equal inputs give equal bytes. */
    fun serialize(contacts: List<Contact>): ByteArray = VCardWriter.write(contacts)

    /**
     * The contact as the parser reads it back from its serialization: text is trimmed and normalized the same way,
     * empty values are gone, and a missing FN is derived. A non-empty UID stays (the parser trims it).
     * [Contact.rev] stays as it is, although the file keeps whole seconds only.
     * Use it to compare a contact with content that went through the vCard format.
     */
    fun canonical(contact: Contact): Contact =
        parse(serialize(listOf(contact))).contacts.firstOrNull()?.copy(rev = contact.rev) ?: contact
}

@Serializable
enum class ConflictKind { FIELDS, DELETED_LOCALLY, DELETED_REMOTELY }

@Serializable
enum class Field { FORMATTED_NAME, NAME, NICKNAME, ORGANIZATION, TITLE, BIRTHDAY, NOTE }

@Serializable
data class Conflict(
    val uid: String,
    val kind: ConflictKind,
    val base: Contact?,
    val local: Contact?,
    val remote: Contact?,
    val fields: List<Field>,
)

data class MergeResult(
    /** Merged contacts, sorted by UID. Conflicting fields hold the provisional (remote) value. */
    val contacts: List<Contact>,
    val conflicts: List<Conflict>,
)

object Merge {
    /** Three-way merge (spec 4.1, 4.2). Inputs need not be sorted. */
    fun merge3(base: List<Contact>, local: List<Contact>, remote: List<Contact>): MergeResult =
        ThreeWayMerge.merge(base, local, remote)

    /**
     * Applies the user's choice for a FIELDS conflict (spec 4.3).
     * [current] is the contact as it is now in local. [useLocal] holds the fields for which the user picked the local value;
     * all other conflicting fields keep the value in [current].
     */
    fun resolveFields(conflict: Conflict, current: Contact, useLocal: Set<Field>, now: Long): Contact =
        ThreeWayMerge.resolveFields(conflict, current, useLocal, now)
}

@Serializable
enum class DiffStatus { ADDED, REMOVED, CHANGED }

@Serializable
data class DiffRow(
    val label: String,
    val left: List<String>,
    val right: List<String>,
    val changed: Boolean,
)

@Serializable
data class ContactDiff(
    val uid: String,
    val displayName: String,
    val status: DiffStatus,
    val rows: List<DiffRow>,
)

object Diff {
    /** Side-by-side diff (spec 4.4). */
    fun diff(left: List<Contact>, right: List<Contact>): List<ContactDiff> = ContactDiffer.diff(left, right)

    /** Diff rows for one contact pair; an absent side gives empty lists. */
    fun rows(left: Contact?, right: Contact?): List<DiffRow> = ContactDiffer.rows(left, right)
}

data class ImportResult(
    val imported: List<Contact>,
    val skippedDuplicates: Int,
)

object Import {
    /**
     * Gives every incoming contact a new UID and drops duplicates of [existing] and of earlier incoming contacts (spec 7).
     * [now] becomes the rev of the imported contacts.
     */
    fun prepare(existing: List<Contact>, incoming: List<Contact>, now: Long): ImportResult =
        ImportDedup.prepare(existing, incoming, now)
}
