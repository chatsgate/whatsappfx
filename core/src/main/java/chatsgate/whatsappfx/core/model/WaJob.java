package chatsgate.whatsappfx.core.model;

import datapotter.arcadedbhelper.ArcadeData;
import datapotter.arcadedbhelper.TypeDef;

import static datapotter.arcadedbhelper.ArcadeType.VERTEX;

/**
 * A long-running piece of work, recorded rather than remembered.
 *
 * <p>Progress kept only in a process's memory or in an open connection is progress that vanishes on a
 * restart or a dropped call — which is exactly how a completed backfill came to look like eight files.
 * The record is the contract; streaming and the window are views onto it.
 */
@ArcadeData(type = VERTEX)
public final class WaJob extends WaJob_A {

    String id;
    String account;
    String verb;
    String args;

    /** running | done | failed | interrupted */
    String state;

    Long total;
    Long done;
    Long failed;
    Long skipped;
    Long bytes;

    Long startedAt;
    Long updatedAt;
    Long finishedAt;
    String error;
    String note;

    public static final TypeDef<WaJob> TYPEDEF =
            schemaBuilder()
                    .factory(WaJob::new)
                    .unique($id)
                    .lsmIndex($account)
                    .lsmIndex($state)
                    .lsmIndex($startedAt)
                    .__();

    public long doneOr0() {
        return done == null ? 0 : done;
    }

    public long totalOr0() {
        return total == null ? 0 : total;
    }
}
