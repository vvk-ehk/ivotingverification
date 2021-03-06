package ee.vvk.ivotingverification;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;

import org.spongycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.spongycastle.operator.ContentVerifierProvider;
import org.spongycastle.operator.jcajce.JcaContentVerifierProviderBuilder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ee.vvk.ivotingverification.dialog.LoadingSpinner;
import ee.vvk.ivotingverification.model.Vote;
import ee.vvk.ivotingverification.util.BDocContainer;
import ee.vvk.ivotingverification.util.C;
import ee.vvk.ivotingverification.util.ElGamalPub;
import ee.vvk.ivotingverification.util.JsonRpc;
import ee.vvk.ivotingverification.util.Ocsp;
import ee.vvk.ivotingverification.util.Pkix;
import ee.vvk.ivotingverification.util.TlsConnection;
import ee.vvk.ivotingverification.util.Util;

/**
 * HTTPS connection with a web server. TLS authentication with own trust store.
 * 
 * @version 16.05.2013
 */
public class VoteDownloadActivity extends Activity {

	private static final String TAG = VoteDownloadActivity.class
			.getSimpleName();

	private LoadingSpinner mLoadingSpinner;
	private String qrCode;
	private byte[] rndSeed;
	private String signerCN;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN);

		setContentView(R.layout.vote_download_activity);

		Intent intent = getIntent();
		qrCode = intent.getStringExtra(Util.EXTRA_MESSAGE);

		LinearLayout frameBg = (LinearLayout) findViewById(R.id.vote_download_frame_bg);
		frameBg.setBackgroundColor(Util
				.generateHexColorValue(C.frameBackground));

		LinearLayout linearLayout = (LinearLayout) findViewById(R.id.target_window_load);
		GradientDrawable bgShape = (GradientDrawable) linearLayout
				.getBackground();
		bgShape.setColor(Util.generateHexColorValue(C.loadingWindow));

		ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
		if (networkInfo != null && networkInfo.isConnected()) {
			new GetVoteTask().execute();
		} else {
			Util.startErrorIntent(VoteDownloadActivity.this,
					C.noNetworkMessage, false);
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		Util.stopSpinner(mLoadingSpinner);
	}

	@SuppressLint("StaticFieldLeak")
	private class GetVoteTask extends AsyncTask<Void, Void, ArrayList<Vote>> {

		@Override
		protected void onPreExecute() {
			mLoadingSpinner = Util
					.startSpinner(VoteDownloadActivity.this, true);
		}

		@Override
		protected ArrayList<Vote> doInBackground(Void... arg0) {
			String[] splitQr = qrCode.split("\n");
			String logSessionId = splitQr[0];
			rndSeed = Base64.decode(splitQr[1], Base64.DEFAULT);
			String voteId = splitQr[2];

			InputStream response;
			try {
				Map<String, Object> params = new HashMap<>();
				params.put("voteid", voteId);
				params.put("sessionid", logSessionId);
				ByteBuffer buf = JsonRpc.createRequest(JsonRpc.Method.VERIFY, params);

				TlsConnection tls = new TlsConnection(C.verificationTlsArray);
				response = tls.sendRequest(C.verificationUrlArray, Util.VERIFICATION_HOSTNAME, buf);
			} catch (UnsupportedOperationException e) {
				if (Util.DEBUGGABLE) {
					Log.e(TAG, "sendRequest error: " + e.getMessage(), e);
				}
				Util.startErrorIntent(VoteDownloadActivity.this,
						C.badDeviceMessage, true);
				return null;
			} catch (Exception e) {
				if (Util.DEBUGGABLE) {
					Log.e(TAG, "sendRequest error: " + e.getMessage(), e);
				}
				Util.startErrorIntent(VoteDownloadActivity.this,
						C.sendServerRequestMessage, true);
				return null;
			}

			try {
				ArrayList<Vote> voteList = new ArrayList<>();
				Map<String, byte[]> votes = readResponse(response);
				for (Map.Entry<String, byte[]> vote: votes.entrySet()) {
					voteList.add(new Vote(vote.getKey(), vote.getValue()));
				}
				return voteList;
			} catch (Exception e) {
				if (Util.DEBUGGABLE) {
					Log.e(TAG, "readResponse error: " + e.getMessage(), e);
				}
				Util.startErrorIntent(VoteDownloadActivity.this,
						C.badServerResponseMessage, true);
				return null;
			}
		}

		private Map<String, byte[]> readResponse(InputStream in) throws Exception {
			JsonRpc.Response res = JsonRpc.unmarshalResponse(JsonRpc.Method.VERIFY, in);
			if (res.error != null) {
				throw new Exception(res.error);
			}
			byte[] containerData = Base64.decode((String) res.result.get("Vote"), Base64.DEFAULT);
			byte[] ocspData = Base64.decode((String) res.result.get("ocsp"), Base64.DEFAULT);
			byte[] regData =  Base64.decode((String) res.result.get("tspreg"), Base64.DEFAULT);

			BDocContainer container = verifyRes(containerData, ocspData, regData);
			return container.getVotes();
		}

		// verifies data received from server. Throws exception on any validation error.
		private BDocContainer verifyRes(byte[] containerData, byte[] ocspData, byte[] regData) throws Exception {
			ElGamalPub pub = new ElGamalPub(C.publicKey);
			BDocContainer container = new BDocContainer(
					new ByteArrayInputStream(containerData), pub.elId);
			container.parseContainer();
			container.validateContainer(Util.getEsteidCerts(VoteDownloadActivity.this));
			signerCN = Util.getCN(container.cert);

			JcaContentVerifierProviderBuilder builder = new JcaContentVerifierProviderBuilder().setProvider("SC");
			List<ContentVerifierProvider> ocspVerifierProviders = new ArrayList<>();
			for (String ocspCertStr: C.ocspServiceCertArray) {
				ocspVerifierProviders.add(builder.build(Util.loadCertificate(ocspCertStr)));
			}
			X509Certificate tspregCert = Util.loadCertificate(C.tspregServiceCert);
			X509Certificate collectorCert = Util.loadCertificate(C.tspregClientCert);

			long producedAt = Ocsp.verifyResponse(new ByteArrayInputStream(ocspData), ocspVerifierProviders, container.cert, container.issuer);

			long genTime = Pkix.verifyResponse(
					regData,
					collectorCert.getPublicKey(),
					new JcaSimpleSignerInfoVerifierBuilder().build(tspregCert),
					container.getSignatureValueCanon());

			long d = genTime - producedAt;
			if (d < 0) {
				throw new Exception("PKIX predates OCSP");
			}
			if (d > Util.MAX_TIME_BETWEEN_OCSP_PKIX) {
				throw new Exception("PKIX and OCSP timestamps too far apart");
			}
			return container;
		}

		@Override
		protected void onPostExecute(ArrayList<Vote> result) {
			Util.stopSpinner(mLoadingSpinner);
			if (result != null) {
				Intent next_intent = new Intent(VoteDownloadActivity.this, VoteActivity.class);
				next_intent.putExtra(Util.SIGNER_CN, signerCN);
				next_intent.putExtra(Util.RANDOM_SEED, rndSeed);
				next_intent.putParcelableArrayListExtra(Util.VOTE_ARRAY, result);
				startActivity(next_intent);
				finish();
			}
		}
	}

}
