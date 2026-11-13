package io.jstach.logwood;

/** Normalize only JVM-assigned metadata; keep every field, separator and record. */
final class GoldenOutput {

	private GoldenOutput() {
	}

	static String normalize(String output) {
		return output.replaceAll("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z", "<time>")
			.replace("thread:" + Thread.currentThread().getName() + "\t", "thread:<thread>\t")
			.replace("\"thread\":\"" + Thread.currentThread().getName() + "\"", "\"thread\":\"<thread>\"")
			.replace("threadId:" + Thread.currentThread().threadId() + "\t", "threadId:<id>\t")
			.replace("\"threadId\":" + Thread.currentThread().threadId() + ",", "\"threadId\":<id>,");
	}

}
