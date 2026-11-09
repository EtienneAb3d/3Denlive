package com.cubaix.TDenlive.depth;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Download of a file (model, ONNX Runtime jar) into a .part file renamed at the end,
 * with progress and cancel
 */
public class Downloader {
	public interface Progress {
		/** aDone/aTotal bytes */
		void downloaded(long aDone,long aTotal);
		boolean isCanceled();
	}

	/**
	 * @return true if the file is there with the expected size (aSize <= 0: any size)
	 */
	public static boolean download(String aUrl,File aFile,long aSize,Progress aProgress) throws Exception {
		if(aFile.isFile() && (aSize <= 0 || aFile.length() == aSize)) {
			return true;
		}
		aFile.getAbsoluteFile().getParentFile().mkdirs();
		File aPart = new File(aFile.getPath()+".part");
		HttpClient aClient = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build();
		HttpRequest aReq = HttpRequest.newBuilder(URI.create(aUrl))
				.header("User-Agent", "3Denlive")
				.GET().build();
		HttpResponse<InputStream> aResp = aClient.send(aReq, HttpResponse.BodyHandlers.ofInputStream());
		if(aResp.statusCode() != 200) {
			aResp.body().close();
			throw new Exception("HTTP "+aResp.statusCode()+": "+aUrl);
		}
		long aTotal = aResp.headers().firstValueAsLong("Content-Length").orElse(aSize);
		long aDone = 0;
		long aLastReport = 0;
		try(InputStream aIS = aResp.body(); OutputStream aOS = new FileOutputStream(aPart)) {
			byte[] aBuf = new byte[1 << 16];
			int aN;
			while((aN = aIS.read(aBuf)) > 0) {
				if(aProgress != null && aProgress.isCanceled()) {
					break;
				}
				aOS.write(aBuf, 0, aN);
				aDone += aN;
				long aNow = System.currentTimeMillis();
				if(aProgress != null && aNow-aLastReport > 200) {
					aLastReport = aNow;
					aProgress.downloaded(aDone, aTotal);
				}
			}
		}
		if((aProgress != null && aProgress.isCanceled()) || (aSize > 0 && aDone != aSize)) {
			aPart.delete();
			if(aProgress != null && aProgress.isCanceled()) {
				return false;
			}
			throw new Exception("Incomplete download ("+aDone+"/"+aSize+"): "+aUrl);
		}
		Files.move(aPart.toPath(), aFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		return true;
	}
}
