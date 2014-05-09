/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 */
package com.kurento.kmf.test.content;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

import com.kurento.kmf.content.WebRtcContentHandler;
import com.kurento.kmf.content.WebRtcContentService;
import com.kurento.kmf.content.WebRtcContentSession;
import com.kurento.kmf.media.MediaPipeline;
import com.kurento.kmf.media.WebRtcEndpoint;
import com.kurento.kmf.test.base.ContentApiTest;
import com.kurento.kmf.test.client.Browser;
import com.kurento.kmf.test.client.BrowserClient;
import com.kurento.kmf.test.client.Client;

/**
 * <strong>Description</strong>: Back-to-back WebRTC Test<br/>
 * <strong>Pipeline</strong>: WebRtcEndpoint -> WebRtcEndpoint<br/>
 * <strong>Pass criteria</strong>: <br/>
 * <ul>
 * <li>Browser #1 and #2 starts before 60 seconds (default timeout)</li>
 * <li>Remote play time in browser #1 and #2 does not differ in a 10% of the
 * transmitting time</li>
 * <li>Browser #1 and #2 stops before 60 seconds (default timeout)</li>
 * </ul>
 * 
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 4.2.3
 */
public class ContentApiWebRtc2WebRtcTest extends ContentApiTest {

	private static final String HANDLER = "/webrtc2webrtc";

	@WebRtcContentService(path = HANDLER)
	public static class WebRtcHandler extends WebRtcContentHandler {

		private WebRtcEndpoint firstWebRtcEndpoint;

		private String sessionId;

		@Override
		public synchronized void onContentRequest(
				WebRtcContentSession contentSession) throws Exception {
			if (firstWebRtcEndpoint == null) {
				// Media Pipeline creation
				MediaPipeline mp = contentSession.getMediaPipelineFactory()
						.create();
				contentSession.releaseOnTerminate(mp);

				// First WebRTC
				firstWebRtcEndpoint = mp.newWebRtcEndpoint().build();
				sessionId = contentSession.getSessionId();
				contentSession.releaseOnTerminate(firstWebRtcEndpoint);

				contentSession.start(firstWebRtcEndpoint);
			} else {
				// Media Pipeline reusing
				MediaPipeline mp = firstWebRtcEndpoint.getMediaPipeline();

				// Next WebRTC endpoints connected to the first one
				WebRtcEndpoint newWebRtcEndpoint = mp.newWebRtcEndpoint()
						.build();
				contentSession.releaseOnTerminate(newWebRtcEndpoint);
				newWebRtcEndpoint.connect(firstWebRtcEndpoint);
				firstWebRtcEndpoint.connect(newWebRtcEndpoint);

				contentSession.start(newWebRtcEndpoint);
			}

			terminateLatch = new CountDownLatch(1);
		}

		@Override
		public void onSessionTerminated(WebRtcContentSession contentSession,
				int code, String reason) throws Exception {
			if (contentSession.getSessionId().equals(sessionId)) {
				getLogger().info("Terminating first WebRTC session");
				firstWebRtcEndpoint = null;
			}
			super.onSessionTerminated(contentSession, code, reason);

			terminateLatch.countDown();
		}

	}

	@Test
	public void testWebRtc2WebRtc() throws InterruptedException {
		try (BrowserClient browser1 = new BrowserClient(getServerPort(),
				Browser.CHROME_FOR_TEST, Client.WEBRTC);
				BrowserClient browser2 = new BrowserClient(getServerPort(),
						Browser.CHROME_FOR_TEST, Client.WEBRTC)) {

			browser1.setURL(HANDLER);
			browser1.subscribeEvents("playing");
			browser1.start();

			// Delay time to avoid the same timing in local-remote video
			Thread.sleep(2000);

			browser2.setURL(HANDLER);
			browser2.subscribeEvents("playing");
			browser2.start();

			// Assertions
			// First, wait in both browser to start their remote streams
			Assert.assertTrue("Timeout waiting playing event",
					browser1.waitForEvent("playing"));
			final double browser1start = System.nanoTime();
			Assert.assertTrue("Timeout waiting playing event",
					browser2.waitForEvent("playing"));
			final double browser2start = System.nanoTime();

			// Guard time to see the communication
			Thread.sleep(10000);

			// Time receiving remote stream (in seconds)
			double browser1time = (System.nanoTime() - browser1start) / 1000000000;
			double browser2time = (System.nanoTime() - browser2start) / 1000000000;
			log.debug("browser1time: {} sec ; browser2time: {} sec ",
					browser1time, browser2time);
			log.debug(
					"browser1.getCurrentTime(): {} sec ; browser2.getCurrentTime(): {} sec ",
					browser1.getCurrentTime(), browser2.getCurrentTime());

			// Comparing expected with real play time
			// Pass criteria: threshold 10 %
			Assert.assertTrue("Error in play time of #1 browser (expected: "
					+ browser1time + " sec, real: " + browser1.getCurrentTime()
					+ " sec)", compare(browser1time, browser1.getCurrentTime()));
			Assert.assertTrue("Error in play time of #2 browser (expected: "
					+ browser2time + " sec, real: " + browser2.getCurrentTime()
					+ " sec)", compare(browser2time, browser2.getCurrentTime()));

			// Ending sessions in both sessions
			browser1.stop();
			Assert.assertTrue("Timeout waiting onSessionTerminated",
					terminateLatch.await(browser1.getTimeout(),
							TimeUnit.SECONDS));
			browser2.stop();
			Assert.assertTrue("Timeout waiting onSessionTerminated",
					terminateLatch.await(browser2.getTimeout(),
							TimeUnit.SECONDS));
		}
	}

}
