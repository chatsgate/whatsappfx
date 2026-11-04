package chatsgate.whatsappfx.daemon;

import com.google.gson.JsonObject;

/** One attachment to fetch, and the job it counts towards. A null job means an ad-hoc single fetch. */
public record MediaTask(String jobId, JsonObject msg) {
}
