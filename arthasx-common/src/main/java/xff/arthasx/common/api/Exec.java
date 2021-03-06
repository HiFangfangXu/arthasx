package xff.arthasx.common.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import xff.arthasx.common.AnsiLog;
import xff.arthasx.common.Constants;
import xff.arthasx.common.Result;
import xff.arthasx.common.util.NamedThreadFactory;

/**
 * 
 * @author Fangfang.Xu
 *
 */
public abstract class Exec {

	protected static final String ARTHASATTACH_PLACEHOLDER_ARTHASXHOME = "${arthasxhome}";
	protected static final String ARTHASATTACH_PLACEHOLDER_TARGETIP = "${targetIp}";
	protected static final String ARTHASATTACH_PLACEHOLDER_TUNNELSERVERADDRESS = "${tunnelServerAddress}";
	protected static final String ARTHASATTACH_PLACEHOLDER_AGENTID = "${agentId}";
	protected static final String ARTHASATTACH_PLACEHOLDER_PID = "${pid}";

	protected static final String ARTHASATTACH_CMD = ARTHASATTACH_PLACEHOLDER_ARTHASXHOME + "/attach.sh --arthasx-home="
			+ ARTHASATTACH_PLACEHOLDER_ARTHASXHOME + " --target-ip=" + ARTHASATTACH_PLACEHOLDER_TARGETIP
			+ " --tunnel-server=" + ARTHASATTACH_PLACEHOLDER_TUNNELSERVERADDRESS + " --agent-id="
			+ ARTHASATTACH_PLACEHOLDER_AGENTID + " --pid=" + ARTHASATTACH_PLACEHOLDER_PID;

	private static final ThreadPoolExecutor CACHED_EXECEXTRACT_THREADPOOL = new ThreadPoolExecutor(
			Integer.parseInt(System.getProperty(Constants.PROPERTIES_ARTHASX_EXECEXTRACT_COREPOOLSIZE, "0")),
			Integer.parseInt(System.getProperty(Constants.PROPERTIES_ARTHASX_EXECEXTRACT_MAXPOOLSIZE, "200")), 60,
			TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
			new NamedThreadFactory("cached-exec-extract-Threadpool"));

	private static final int EXECEXTRACT_TIMEOUTMILLIS = Integer
			.parseInt(System.getProperty(Constants.PROPERTIES_ARTHASX_EXECEXTRACT_TIMEOUTMILLIS, "1000"));

	public ExecResult exec(String... cmds) throws IOException {
		return exec(null, null, null, cmds);
	}

	public abstract ExecResult exec(List<String> includes, List<String> excludes, Integer lineIndex, String... cmds)
			throws IOException;

	public abstract ExecResult getJVMPids(String jpsKeywords) throws IOException;

	public Result<Void> arthasAttach(String arthasxhome, String tunnelServerAddress, String jpsKeywords,
			String targetIp, String agentId) throws IOException {
		// get target jvm pid
		ExecResult execResult = getJVMPids(jpsKeywords);
		if (!execResult.isSuccess()) {
			return Result.builder().buildFailed("get JVMPid failed, messages:" + execResult.toStringFullMessages());
		}
		List<String> jvmPids = execResult.getLines();
		if (jvmPids.size() != 1) {
			return Result.builder().buildFailed("JVMPid not exactly found, jpsKeywords:" + jpsKeywords
					+ " ,found JVMPids:" + jvmPids + " ,messages:" + execResult.toStringFullMessages());
		}

		final String cmd = ARTHASATTACH_CMD.replace(ARTHASATTACH_PLACEHOLDER_AGENTID, agentId)
				.replace(ARTHASATTACH_PLACEHOLDER_ARTHASXHOME, arthasxhome)
				.replace(ARTHASATTACH_PLACEHOLDER_TARGETIP, targetIp)
				.replace(ARTHASATTACH_PLACEHOLDER_TUNNELSERVERADDRESS, tunnelServerAddress)
				.replace(ARTHASATTACH_PLACEHOLDER_PID, jvmPids.get(0));

		// attach
		execResult = doArthasAttach(cmd);

		AnsiLog.info("attach info:");
		AnsiLog.info(execResult.toStringFullMessages());
		if (!execResult.isSuccess()) {
			return Result.builder().buildFailed("attach failed, messages:" + execResult.toStringFullMessages());
		}
		return Result.builder().buildSuccess(null);
	}

	protected abstract ExecResult doArthasAttach(String cmd) throws IOException;

	protected ExecResult extractResponse(final Process p, final List<String> includes, final List<String> excludes,
			final Integer lineIndex) throws IOException {
		try {
			p.waitFor(3000, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			AnsiLog.warn("exec extract wait timeout", e);
		}
		Integer exitValue = null;
		final List<String> lines = new ArrayList<String>(0);
		final List<String> fullMessages = new ArrayList<String>(0);
		try {
			Future<Void> future = CACHED_EXECEXTRACT_THREADPOOL.submit(new Callable<Void>() {
				@Override
				public Void call() throws Exception {
					BufferedReader reader = null;
					try {
						reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
						String line;
						while ((line = reader.readLine()) != null) {
							fullMessages.add(line);
							if (includes != null) {
								for (String include : includes) {
									if (!line.contains(include)) {
										line = null;
										break;
									}
								}
							}
							if (line != null) {
								if (excludes != null) {
									for (String exclude : excludes) {
										if (line.contains(exclude)) {
											line = null;
											break;
										}
									}
								}
							}
							if (line != null) {
								if (lineIndex != null) {
									lines.add(line.split("\\s+")[lineIndex]);
								} else {
									lines.add(line);
								}
							}
						}
					} finally {
						if (reader != null) {
							reader.close();
						}
					}
					return null;
				}
			});
			// timed wait
			future.get(EXECEXTRACT_TIMEOUTMILLIS, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			exitValue = -1;
			String msg = "too many attaches current time";
			lines.add(msg);
			fullMessages.add(msg);
		} catch (TimeoutException e) {
			//
		} catch (InterruptedException e) {
			//
		} catch (ExecutionException e) {
			throw new RuntimeException(e);
		} finally {
			p.destroy();
			if (exitValue == null) {
				exitValue = p.exitValue();
			}
		}
		return new ExecResult(exitValue, lines, fullMessages);
	}

//	protected ExecResult extractResponse(Process p, List<String> includes, List<String> excludes, Integer lineIndex)
//			throws IOException {
//		try {
//			try {
//				p.waitFor(3000, TimeUnit.MILLISECONDS);
//			} catch (Exception e) {
//				//
//			}
//			List<String> lines = new ArrayList<String>(0);
//			List<String> fullMessages = new ArrayList<String>(0);
//			BufferedReader reader = null;
//			try {
//				reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
//				String line;
//				while ((line = reader.readLine()) != null) {
//					fullMessages.add(line);
//					if (includes != null) {
//						for (String include : includes) {
//							if (!line.contains(include)) {
//								line = null;
//								break;
//							}
//						}
//					}
//					if (line != null) {
//						if (excludes != null) {
//							for (String exclude : excludes) {
//								if (line.contains(exclude)) {
//									line = null;
//									break;
//								}
//							}
//						}
//					}
//					if (line != null) {
//						if (lineIndex != null) {
//							lines.add(line.split("\\s+")[lineIndex]);
//						} else {
//							lines.add(line);
//						}
//					}
//				}
//			}finally {
//				if(reader != null) {
//					reader.close();
//				}
//			}
//			return new ExecResult(p.exitValue(), lines, fullMessages);
//		} finally {
//			p.destroy();
//		}
//	}

	public static class ExecResult {
		private int exitValue;
		private List<String> lines;
		private List<String> fullMessages;

		public ExecResult(int exitValue, List<String> lines, List<String> fullMessages) {
			this.exitValue = exitValue;
			this.lines = lines;
			this.fullMessages = fullMessages;
		}

		public boolean isSuccess() {
			return exitValue == 0;
		}

		public int getExitValue() {
			return exitValue;
		}

		public List<String> getLines() {
			return lines;
		}

		public String toStringFullMessages() {
			return fullMessages.stream().collect(Collectors.joining(";\n"));
		}
	}
}
