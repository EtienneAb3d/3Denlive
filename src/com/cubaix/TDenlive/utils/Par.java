package com.cubaix.TDenlive.utils;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Parallel computations of an image on reused threads (no thread created per processor and per image)
 * <ul>
 * <li>{@link Task}: started and joined like a Thread (left / right views, lines of the montage)</li>
 * <li>{@link #rows(int, Rows)}: lines of an image cut in bands, computed on all the cores</li>
 * </ul>
 * The pool grows when needed (cached): tasks waiting for other tasks never block it.
 */
public final class Par {
	public static final int NB_CPU = Math.max(1, Runtime.getRuntime().availableProcessors());
	//Lines per band at least (a band smaller costs more to start than it computes)
	static final int BAND_MIN_LINES = 16;

	static final ExecutorService POOL = Executors.newCachedThreadPool(aR -> {
		Thread aT = new Thread(aR, "3De-Par");
		aT.setDaemon(true);
		return aT;
	});

	private Par() {
	}

	/**
	 * A runnable started on the pool, joined like a Thread (its errors are printed, as an uncaught exception of a thread)
	 */
	public static final class Task {
		final Runnable run;
		Future<?> future = null;

		public Task(Runnable aRun) {
			run = aRun;
		}

		public void start() {
			future = POOL.submit(() -> {
				try {
					run.run();
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			});
		}

		public void join() throws InterruptedException {
			if(future == null) {
				return;
			}
			try {
				future.get();
			}
			catch(ExecutionException e) {
				(e.getCause() != null ? e.getCause() : e).printStackTrace(System.err);
			}
		}
	}

	/** Lines [aY0, aY1[ of an image */
	public interface Rows {
		void run(int aY0, int aY1);
	}

	/**
	 * Number of bands of lines rows() and each() use for aH lines
	 */
	public static int bandsFor(int aH) {
		return Math.max(1, Math.min(NB_CPU, aH/BAND_MIN_LINES));
	}

	/**
	 * aBody(0..aN-1) on all the cores (the last one on the calling thread); returns when all are done
	 */
	public static void each(int aN, java.util.function.IntConsumer aBody) {
		if(aN <= 1) {
			if(aN == 1) {
				aBody.accept(0);
			}
			return;
		}
		Future<?>[] aFs = new Future<?>[aN-1];
		for(int b = 0;b < aN-1;b++) {
			int aB = b;
			aFs[b] = POOL.submit(() -> aBody.accept(aB));
		}
		Throwable aErr = null;
		try {
			aBody.accept(aN-1);
		}
		catch(Throwable t) {
			aErr = t;
		}
		for(Future<?> aF : aFs) {
			try {
				aF.get();
			}
			catch(ExecutionException e) {
				if(aErr == null) {
					aErr = e.getCause() != null ? e.getCause() : e;
				}
			}
			catch(InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if(aErr instanceof RuntimeException) {
			throw (RuntimeException)aErr;
		}
		if(aErr instanceof Error) {
			throw (Error)aErr;
		}
	}

	/**
	 * Lines 0..aH-1 cut in bands computed on all the cores (the last one on the calling thread); returns when all are done
	 */
	public static void rows(int aH, Rows aRows) {
		int aNb = Math.min(NB_CPU, aH/BAND_MIN_LINES);
		if(aNb <= 1) {
			aRows.run(0, aH);
			return;
		}
		Future<?>[] aFs = new Future<?>[aNb-1];
		for(int b = 0;b < aNb-1;b++) {
			int aY0 = b*aH/aNb;
			int aY1 = (b+1)*aH/aNb;
			aFs[b] = POOL.submit(() -> aRows.run(aY0, aY1));
		}
		Throwable aErr = null;
		try {
			aRows.run((aNb-1)*aH/aNb, aH);
		}
		catch(Throwable t) {
			aErr = t;
		}
		for(Future<?> aF : aFs) {
			try {
				aF.get();
			}
			catch(ExecutionException e) {
				if(aErr == null) {
					aErr = e.getCause() != null ? e.getCause() : e;
				}
			}
			catch(InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if(aErr instanceof RuntimeException) {
			throw (RuntimeException)aErr;
		}
		if(aErr instanceof Error) {
			throw (Error)aErr;
		}
	}
}
