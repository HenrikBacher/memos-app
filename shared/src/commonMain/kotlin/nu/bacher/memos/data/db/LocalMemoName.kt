package nu.bacher.memos.data.db

/**
 * Naming rule for client-issued memo rows — the optimistic placeholders
 * [nu.bacher.memos.data.repo.MemoRepository.create] inserts before the server
 * has issued a real resource name.
 *
 * Lives in the db layer because it's a storage concern: the DAO's queries bind
 * [PREFIX] as a LIKE pattern (Room can't interpolate a const into `@Query`),
 * and everything else asks [isLocal] rather than re-spelling `startsWith`.
 */
object LocalMemoName {
    const val PREFIX: String = "memos/local-"

    fun isLocal(name: String): Boolean = name.startsWith(PREFIX)
}
