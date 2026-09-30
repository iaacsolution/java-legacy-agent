package com.audensiel.legacy.agent.observability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Puits en mémoire, thread-safe : permet de vérifier ce qui aurait été écrit sans
 * base de données, y compris depuis plusieurs threads worker.
 */
final class RecordingSink implements SpanSink {

    private final List<SpanRecord> records = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void accept(SpanRecord record) {
        records.add(record);
    }

    @Override
    public void close() {
        // rien à libérer
    }

    /** Copie défensive — l'itération reste sûre pendant que des workers écrivent encore. */
    List<SpanRecord> records() {
        synchronized (records) {
            return new ArrayList<>(records);
        }
    }

    int size() {
        return records.size();
    }
}
