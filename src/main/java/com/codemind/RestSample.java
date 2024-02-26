package com.codemind;

import org.apache.http.*;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.ssl.SSLContextBuilder;
import org.json.JSONObject;

import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * CODEMIND REST api example code
 *      - login
 *      - analyze
 *      - check analysis status
 *      - analysis result
 *      - checkLogin
 *
 *  테스트 전 코드마인드에 프로젝트(ex. kr.codemind.lab)가 등록 되어있어야 합니다. (git or 소스경로지정)
 *  아래 서버 URL 및 인증정보 그리고 프로젝트 명을 기입 한 후 실행하면 됩니다.
 */
public class RestSample {
    static CloseableHttpClient httpclient;
    static final int REQUEST_TIMEOUT = 3600;        // 1 hour
    static final String CODEMIND_URL = "http://10.0.1.123:8083";
    static final String USERNAME = "openapi";
    static final String PASSWORD = "codemind@2";
    static final String PROJECT_NAME = "project_1";

    static String csrf = "";

    private static void createHttpClient() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        SSLContextBuilder sslContextBuilder = new SSLContextBuilder();
        sslContextBuilder.loadTrustMaterial(null, new TrustSelfSignedStrategy());
        SSLConnectionSocketFactory sslConnectionSocketFactory = new SSLConnectionSocketFactory(sslContextBuilder.build(), NoopHostnameVerifier.INSTANCE);

        httpclient = HttpClients.custom()
                .setSSLSocketFactory(sslConnectionSocketFactory)
                .build();
    }

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        createHttpClient();

        login();

        checkLogin();

        analyze(PROJECT_NAME);

        int seq = checkAnal(PROJECT_NAME);

        loadAnalysisResult(PROJECT_NAME, seq);

        loadAnalysisResultRuleStatistics(PROJECT_NAME, seq);

        addProject();
        updateProject();
        deleteProject();
    }

    /**
     * 로그인
     * @throws IOException
     */
    private static void login() throws IOException {
        HttpPost post = new HttpPost(CODEMIND_URL + "/user/login/process");
        List<NameValuePair> entity = new ArrayList();
        entity.add(new BasicNameValuePair("REQUEST_KIND", "API"));
        entity.add(new BasicNameValuePair("username", USERNAME));
        entity.add(new BasicNameValuePair("password", PASSWORD));
        post.setEntity(new UrlEncodedFormEntity(entity));

        CloseableHttpResponse response = null;
        try {
            response = httpclient.execute(post);
            String content = new BasicResponseHandler().handleEntity(response.getEntity());
            if( response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                if(content.startsWith("CM-")) {
                    // application fail
                    throw new LoginException("login failed: " + content);
                }
                else {
                    // success
                    // cookie = response.getFirstHeader("Set-Cookie").getValue();       // request header에 cookie 자동적용
                    System.out.println("login successfully");
                }
            }
            else {
                throw new HttpResponseException(response.getStatusLine().getStatusCode(), content);
            }
        }
        catch(Exception ex) {
            ex.printStackTrace();
        }
        finally {
            if(response != null) response.close();
        }
    }

    /**
     * 프로젝트 분석 요청
     * @param projectName
     * @throws IOException
     */
    private static void analyze(String projectName) throws IOException {
        HttpPost post = new HttpPost(CODEMIND_URL + "/api/analysis/" + projectName);      // 현재 API
        CloseableHttpResponse response = null;
        try {
            response = httpclient.execute(post);
            String content = new BasicResponseHandler().handleEntity(response.getEntity());
            if( response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                JSONObject json = new JSONObject(content);
                throw new InvalidParameterException(json.getString("message"));
            }
            else {
                System.out.println("start analyzing...");
            }
        } finally {
            if(response != null) response.close();
        }
    }

    /**
     * 프로젝트 분석 진행상태 체크
     * @param projectName
     * @throws IOException
     */
    private static int checkAnal(String projectName) throws IOException {
        CloseableHttpResponse response = null;
        int sequence = 0;
        try {
            int count = 0;
            while(true) {
                if( count >= REQUEST_TIMEOUT ) {
                    System.out.println(String.format("[%s] analysis failed: request timeout %s sec.", projectName, count));
                    break;
                }
                HttpGet get = new HttpGet(CODEMIND_URL + "/api/" + projectName + "/status");
                response = httpclient.execute(get);
                String content = new BasicResponseHandler().handleEntity(response.getEntity());
                if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                    JSONObject json = new JSONObject(content);
                    String status = json.getString("status");
                    if (status.equals("success")) {
                        // success
                        System.out.println("analysis successfully");
                        sequence = Integer.parseInt(Optional.ofNullable(json.getString("sequence")).orElse("0"));       // numOfReports > sequence 로 변경 - 23/03/07
                        break;
                    } else if (isRunning(status)) {     // 진행중 상태체크 변경 - 23/03/09
                        // noop
                        System.out.println(status + "..." + count);
                    } else {
                        // analysis failed
                        System.out.println("analysis failed");
                        break;
                    }
                } else {
                    throw new InvalidParameterException(content);
                }

                count += 5;
                TimeUnit.SECONDS.sleep(5);
            }
        } catch (InterruptedException e) {
            // noop
        } finally {
            if(response != null) response.close();
        }
        return sequence;
    }

    /**
     * 진행중인 상태 체크
     * @param status
     * @return
     */
    private static boolean isRunning(String status) {
        if(!status.equals("success") && !status.equals("stop") && !status.equals("fail") && !status.equals("")) {
            return true;
        }
        return false;
    }

    /**
     * 프로젝트 분석 결과 조회
     * @param projectName
     * @param sequence
     */
    private static void loadAnalysisResult(String projectName, int sequence) throws IOException {
        String url = CODEMIND_URL + "/api/" + projectName + "/" + sequence + "/analysis-result";
        System.out.println("URL: " + url);
        HttpGet get = new HttpGet(url);
        CloseableHttpResponse response = httpclient.execute(get);
        String content = new BasicResponseHandler().handleEntity(response.getEntity());
        if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
            JSONObject json = new JSONObject(content);
            // 조회된 결과에는 아래와 같이 분석결과 요약정보 및 각 파일에 상세 취약점 정보가 포함됩니다.
            // warns: [] - 취약점 목록, files: {} - 파일 목록, canons: [] - 규칙 목록
            System.out.println("------------------------------------------------------------------------------");
            System.out.println("project: " + json.getString("project") +
                    ", totalLines: " + json.getInt("totalLines") +
                    ", files: " + json.getJSONObject("files").length() +
                    ", vulnerability: " + json.getJSONArray("warns").length() +
                    ", startTime: " + json.getInt("startTime") +
                    ", endTime: " + json.getInt("endTime"));



            //    {
            //      "user": "admin",
            //      "project": "kr.codemind.lab",
            //      "startTime": 1668668439517,
            //      "endTime": 1668668599050,
            //      "ruleset": {
            //        "id": 0,
            //        "name": "",
            //        "title": ""
            //      },
            //      "files": {
            //        "D:/workspace/murph/Cooper/target/package/CooperData/ProjectRepos/kr.codemind.lab/src/main/java/com/thealgorithms/maths/PrimeCheck.java": {
            //          "path": "D:/workspace/murph/Cooper/target/package/CooperData/ProjectRepos/kr.codemind.lab/src/main/java/com/thealgorithms/maths/PrimeCheck.java",
            //          "code": "package@{b}com.thealgorithms.maths;@{n}@{n}import@{b}java.util.Scanner;@{n}@{n}public@{b}class@{b}PrimeCheck@{b}{@{n}@{n}@{b}@{b}@{b}@{b}public@{b}static@{b}void@{b}main(String[]@{b}args)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}Scanner@{b}scanner@{b}=@{b}new@{b}Scanner(System.in);@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}System.out.print(\"Enter@{b}a@{b}number:@{b}\");@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}int@{b}n@{b}=@{b}scanner.nextInt();@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(isPrime(n))@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}System.out.println(\"algo1@{b}verify@{b}that@{b}\"@{b}+@{b}n@{b}+@{b}\"@{b}is@{b}a@{b}prime@{b}number\");@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{b}else@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}System.out.println(@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}\"algo1@{b}verify@{b}that@{b}\"@{b}+@{b}n@{b}+@{b}\"@{b}is@{b}not@{b}a@{b}prime@{b}number\"@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b});@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(fermatPrimeChecking(n,@{b}20))@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}System.out.println(\"algo2@{b}verify@{b}that@{b}\"@{b}+@{b}n@{b}+@{b}\"@{b}is@{b}a@{b}prime@{b}number\");@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{b}else@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}System.out.println(@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}\"algo2@{b}verify@{b}that@{b}\"@{b}+@{b}n@{b}+@{b}\"@{b}is@{b}not@{b}a@{b}prime@{b}number\"@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b});@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}scanner.close();@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}/**@{n}@{b}@{b}@{b}@{b}@{b}*@{b}*@{n}@{b}@{b}@{b}@{b}@{b}*@{b}Checks@{b}if@{b}a@{b}number@{b}is@{b}prime@{b}or@{b}not@{n}@{b}@{b}@{b}@{b}@{b}*@{n}@{b}@{b}@{b}@{b}@{b}*@{b}@{@}param@{b}n@{b}the@{b}number@{n}@{b}@{b}@{b}@{b}@{b}*@{b}@{@}return@{b}{@{@}code@{b}true}@{b}if@{b}{@{@}code@{b}n}@{b}is@{b}prime@{n}@{b}@{b}@{b}@{b}@{b}*/@{n}@{b}@{b}@{b}@{b}public@{b}static@{b}boolean@{b}isPrime(int@{b}n)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(n@{b}==@{b}2)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}true;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(n@{b}<@{b}2@{b}||@{b}n@{b}%@{b}2@{b}==@{b}0)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}false;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}for@{b}(int@{b}i@{b}=@{b}3,@{b}limit@{b}=@{b}(int)@{b}Math.sqrt(n);@{b}i@{b}<=@{b}limit;@{b}i@{b}+=@{b}2)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(n@{b}%@{b}i@{b}==@{b}0)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}false;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}true;@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}/**@{n}@{b}@{b}@{b}@{b}@{b}*@{b}*@{n}@{b}@{b}@{b}@{b}@{b}*@{b}Checks@{b}if@{b}a@{b}number@{b}is@{b}prime@{b}or@{b}not@{n}@{b}@{b}@{b}@{b}@{b}*@{n}@{b}@{b}@{b}@{b}@{b}*@{b}@{@}param@{b}n@{b}the@{b}number@{n}@{b}@{b}@{b}@{b}@{b}*@{b}@{@}return@{b}{@{@}code@{b}true}@{b}if@{b}{@{@}code@{b}n}@{b}is@{b}prime@{n}@{b}@{b}@{b}@{b}@{b}*/@{n}@{b}@{b}@{b}@{b}public@{b}static@{b}boolean@{b}fermatPrimeChecking(int@{b}n,@{b}int@{b}iteration)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}long@{b}a;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}int@{b}up@{b}=@{b}n@{b}-@{b}2,@{b}down@{b}=@{b}2;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}for@{b}(int@{b}i@{b}=@{b}0;@{b}i@{b}<@{b}iteration;@{b}i++)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}a@{b}=@{b}(long)@{b}Math.floor(Math.random()@{b}*@{b}(up@{b}-@{b}down@{b}+@{b}1)@{b}+@{b}down);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(modPow(a,@{b}n@{b}-@{b}1,@{b}n)@{b}!=@{b}1)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}false;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}true;@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}/**@{n}@{b}@{b}@{b}@{b}@{b}*@{b}*@{n}@{b}@{b}@{b}@{b}@{b}*@{b}@{@}param@{b}a@{b}basis@{n}@{b}@{b}@{b}@{b}@{b}*@{b}@{@}param@{b}b@{b}exponent@{n}@{b}@{b}@{b}@{b}@{b}*@{b}@{@}param@{b}c@{b}modulo@{n}@{b}@{b}@{b}@{b}@{b}*@{b}@{@}return@{b}(a^b)@{b}mod@{b}c@{n}@{b}@{b}@{b}@{b}@{b}*/@{n}@{b}@{b}@{b}@{b}private@{b}static@{b}long@{b}modPow(long@{b}a,@{b}long@{b}b,@{b}long@{b}c)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}long@{b}res@{b}=@{b}1;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}for@{b}(int@{b}i@{b}=@{b}0;@{b}i@{b}<@{b}b;@{b}i++)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}res@{b}*=@{b}a;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}res@{b}%=@{b}c;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}res@{b}%@{b}c;@{n}@{b}@{b}@{b}@{b}}@{n}}",
            //          "line": 87
            //        },
            //        "D:/workspace/murph/Cooper/target/package/CooperData/ProjectRepos/kr.codemind.lab/src/main/java/com/thealgorithms/datastructures/trees/AVLTree.java": {
            //          "path": "D:/workspace/murph/Cooper/target/package/CooperData/ProjectRepos/kr.codemind.lab/src/main/java/com/thealgorithms/datastructures/trees/AVLTree.java",
            //          "code": "package@{b}com.thealgorithms.datastructures.trees;@{n}@{n}public@{b}class@{b}AVLTree@{b}{@{n}@{n}@{b}@{b}@{b}@{b}private@{b}Node@{b}root;@{n}@{n}@{b}@{b}@{b}@{b}private@{b}class@{b}Node@{b}{@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}private@{b}int@{b}key;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}private@{b}int@{b}balance;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}private@{b}int@{b}height;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}private@{b}Node@{b}left,@{b}right,@{b}parent;@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}Node(int@{b}k,@{b}Node@{b}p)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}key@{b}=@{b}k;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}parent@{b}=@{b}p;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}public@{b}boolean@{b}insert(int@{b}key)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(root@{b}==@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}root@{b}=@{b}new@{b}Node(key,@{b}null);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{b}else@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}Node@{b}n@{b}=@{b}root;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}Node@{b}parent;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}while@{b}(true)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(n.key@{b}==@{b}key)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}false;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}parent@{b}=@{b}n;@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}boolean@{b}goLeft@{b}=@{b}n.key@{b}>@{b}key;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}n@{b}=@{b}goLeft@{b}?@{b}n.left@{b}:@{b}n.right;@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(n@{b}==@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(goLeft)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}parent.left@{b}=@{b}new@{b}Node(key,@{b}parent);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{b}else@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}parent.right@{b}=@{b}new@{b}Node(key,@{b}parent);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}rebalance(parent);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}break;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}true;@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}private@{b}void@{b}delete(Node@{b}node)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(node.left@{b}==@{b}null@{b}&&@{b}node.right@{b}==@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(node.parent@{b}==@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}root@{b}=@{b}null;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{b}else@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}Node@{b}parent@{b}=@{b}node.parent;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(parent.left@{b}==@{b}node)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}parent.left@{b}=@{b}null;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{b}else@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}parent.right@{b}=@{b}null;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}rebalance(parent);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(node.left@{b}!=@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}Node@{b}child@{b}=@{b}node.left;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}while@{b}(child.right@{b}!=@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}child@{b}=@{b}child.right;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}node.key@{b}=@{b}child.key;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}delete(child);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{b}else@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}Node@{b}child@{b}=@{b}node.right;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}while@{b}(child.left@{b}!=@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}child@{b}=@{b}child.left;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}node.key@{b}=@{b}child.key;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}delete(child);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}public@{b}void@{b}delete(int@{b}delKey)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(root@{b}==@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}Node@{b}node@{b}=@{b}root;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}Node@{b}child@{b}=@{b}root;@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}while@{b}(child@{b}!=@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}node@{b}=@{b}child;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}child@{b}=@{b}delKey@{b}>=@{b}node.key@{b}?@{b}node.right@{b}:@{b}node.left;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(delKey@{b}==@{b}node.key)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}delete(node);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}private@{b}void@{b}rebalance(Node@{b}n)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}setBalance(n);@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(n.balance@{b}==@{b}-2)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(height(n.left.left)@{b}>=@{b}height(n.left.right))@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}n@{b}=@{b}rotateRight(n);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{b}else@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}n@{b}=@{b}rotateLeftThenRight(n);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{b}else@{b}if@{b}(n.balance@{b}==@{b}2)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(height(n.right.right)@{b}>=@{b}height(n.right.left))@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}n@{b}=@{b}rotateLeft(n);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{b}else@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}n@{b}=@{b}rotateRightThenLeft(n);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(n.parent@{b}!=@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}rebalance(n.parent);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{b}else@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}root@{b}=@{b}n;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}private@{b}Node@{b}rotateLeft(Node@{b}a)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}Node@{b}b@{b}=@{b}a.right;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}b.parent@{b}=@{b}a.parent;@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}a.right@{b}=@{b}b.left;@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(a.right@{b}!=@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}a.right.parent@{b}=@{b}a;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}b.left@{b}=@{b}a;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}a.parent@{b}=@{b}b;@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(b.parent@{b}!=@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(b.parent.right@{b}==@{b}a)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}b.parent.right@{b}=@{b}b;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{b}else@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}b.parent.left@{b}=@{b}b;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}setBalance(a,@{b}b);@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}b;@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}private@{b}Node@{b}rotateRight(Node@{b}a)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}Node@{b}b@{b}=@{b}a.left;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}b.parent@{b}=@{b}a.parent;@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}a.left@{b}=@{b}b.right;@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(a.left@{b}!=@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}a.left.parent@{b}=@{b}a;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}b.right@{b}=@{b}a;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}a.parent@{b}=@{b}b;@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(b.parent@{b}!=@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(b.parent.right@{b}==@{b}a)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}b.parent.right@{b}=@{b}b;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{b}else@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}b.parent.left@{b}=@{b}b;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}setBalance(a,@{b}b);@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}b;@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}private@{b}Node@{b}rotateLeftThenRight(Node@{b}n)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}n.left@{b}=@{b}rotateLeft(n.left);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}rotateRight(n);@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}private@{b}Node@{b}rotateRightThenLeft(Node@{b}n)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}n.right@{b}=@{b}rotateRight(n.right);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}rotateLeft(n);@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}private@{b}int@{b}height(Node@{b}n)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(n@{b}==@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}-1;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}n.height;@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}private@{b}void@{b}setBalance(Node...@{b}nodes)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}for@{b}(Node@{b}n@{b}:@{b}nodes)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}reheight(n);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}n.balance@{b}=@{b}height(n.right)@{b}-@{b}height(n.left);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}public@{b}void@{b}printBalance()@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}printBalance(root);@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}private@{b}void@{b}printBalance(Node@{b}n)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(n@{b}!=@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}printBalance(n.left);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}System.out.printf(\"%s@{b}\",@{b}n.balance);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}printBalance(n.right);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}private@{b}void@{b}reheight(Node@{b}node)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(node@{b}!=@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}node.height@{b}=@{b}1@{b}+@{b}Math.max(height(node.left),@{b}height(node.right));@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}public@{b}boolean@{b}search(int@{b}key)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}Node@{b}result@{b}=@{b}searchHelper(this.root,@{b}key);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(result@{b}!=@{b}null)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}true;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}false;@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}private@{b}Node@{b}searchHelper(Node@{b}root,@{b}int@{b}key)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}//@{b}root@{b}is@{b}null@{b}or@{b}key@{b}is@{b}present@{b}at@{b}root@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(root@{b}==@{b}null@{b}||@{b}root.key@{b}==@{b}key)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}root;@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}//@{b}key@{b}is@{b}greater@{b}than@{b}root's@{b}key@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}if@{b}(root.key@{b}>@{b}key)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}searchHelper(root.left,@{b}key);@{b}//@{b}call@{b}the@{b}function@{b}on@{b}the@{b}node's@{b}left@{b}child@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}//@{b}key@{b}is@{b}less@{b}than@{b}root's@{b}key@{b}then@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}//@{b}call@{b}the@{b}function@{b}on@{b}the@{b}node's@{b}right@{b}child@{b}as@{b}it@{b}is@{b}greater@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}return@{b}searchHelper(root.right,@{b}key);@{n}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}public@{b}static@{b}void@{b}main(String[]@{b}args)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}AVLTree@{b}tree@{b}=@{b}new@{b}AVLTree();@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}System.out.println(\"Inserting@{b}values@{b}1@{b}to@{b}10\");@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}for@{b}(int@{b}i@{b}=@{b}1;@{b}i@{b}<@{b}10;@{b}i++)@{b}{@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}tree.insert(i);@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}}@{n}@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}System.out.print(\"Printing@{b}balance:@{b}\");@{n}@{b}@{b}@{b}@{b}@{b}@{b}@{b}@{b}tree.printBalance();@{n}@{b}@{b}@{b}@{b}}@{n}}",
            //          "line": 253
            //        }
            //      },
            //      "totalLines": 15549,
            //      "canons": {
            //        "0052_THROWBROAD": {
            //          "id": "0052_THROWBROAD",
            //          "name": "포괄적인 Exception throw 선언",
            //          "cwe": "CWE-397",
            //          "risky": 2,
            //          "description": "메소드 선언 시 Exception 또는 Throwable을 throw 하도록 하면 호출하는 쪽에서 적절한 오류 처리를 할 수 없게 됩니다."
            //        },
            //        "002D_ASSTOPRIVA": {
            //          "id": "002D_ASSTOPRIVA",
            //          "name": "Private 배열에 Public 데이터 할당",
            //          "cwe": "CWE-496",
            //          "risky": 3,
            //          "description": "public으로 선언된 메소드의 인자가 private선언된 배열에 저장되면, private배열을 외부에서 접근하여 배열수정과 객체 속성변경이 가능해집니다."
            //        },
            //        "0060_BLOCKEMPTY": {
            //          "id": "0060_BLOCKEMPTY",
            //          "name": "비어있는 블록",
            //          "cwe": "CWE-398",
            //          "risky": 1,
            //          "description": "비어있는 블록은 아무런 동작을 하지 않으므로 제거되거나 적절한 동작을 수행하도록 수정되어야 합니다. 나쁜 품질의 코드는 직접적으로 약점이나 취약점으로 이르지는 않지만, 소프트웨어 제품이 잘 개발되지 않았거나 유지보수되고 있지 않음을 나타낼 수 있습니다."
            //        },
            //        "0023_NOACTION": {
            //          "id": "0023_NOACTION",
            //          "name": "오류 상황 대응 부재",
            //          "cwe": "CWE-390",
            //          "risky": 4,
            //          "description": "오류가 발생할 수 있는 부분을 확인하였으나, 이러한 오류에 대하여 예외 처리를 하지 않을 경우, 공격자는 오류 상황을 악용하여 개발자가 의도하지 않은 방향으로 프로그램이 동작하도록 할 수 있습니다."
            //        },
            //        "7003_LEAKERRORMSG": {
            //          "id": "7003_LEAKERRORMSG",
            //          "name": "오류메시지를 통한 정보노출",
            //          "cwe": "CWE-209,CWE-497",
            //          "risky": 3,
            //          "description": "오류메시지나 스택정보에 시스템 내부구조가 포함되어 민감한 정보, 디버깅 정보가 노출 가능합니다."
            //        },
            //        "0068_MISSDEFAL": {
            //          "id": "0068_MISSDEFAL",
            //          "name": "switch문에서 default case 누락",
            //          "cwe": "CWE-478",
            //          "risky": 1,
            //          "description": "default case가 누락된 switch 문은 복잡한 논리적 오류 등의 원인이 됩니다."
            //        },
            //        "001C_LEAKCOM": {
            //          "id": "001C_LEAKCOM",
            //          "name": "주석문 안에 포함된 패스워드 등 시스템 주요정보",
            //          "cwe": "CWE-615",
            //          "risky": 4,
            //          "description": "패스워드를 주석문에 넣어두면 시스템 보안이 훼손될 수 있습니다. 소프트웨어 개발자가 편의를 위해서 주석문에 패스워드를 적어둔 경우, 소프트웨어가 완성된 후에는 그것을 제거하는 것이 매우 어렵게 됩니다. 또한 공격자가 소스코드에 접근할 수 있다면, 아주 쉽게 시스템에 침입할 수 있습니다."
            //        },
            //        "0048_MISSBREAK": {
            //          "id": "0048_MISSBREAK",
            //          "name": "switch문에서 누락된 break",
            //          "cwe": "CWE-484",
            //          "risky": 3,
            //          "description": "switch나 그와 유사한 구조물에서 break 문이 누락되면 다중 조건들과 연관된 코드가 실행될 수 있습니다. 이것은 프로그래머가 원래 하나의 조건과 연관된 코드만 실행되도록 의도했다면 문제가 될 수 있습니다."
            //        },
            //        "005B_TRUE": {
            //          "id": "005B_TRUE",
            //          "name": "항상 참인 조건식",
            //          "cwe": "CWE-571",
            //          "risky": 1,
            //          "description": "항상 true인 조건식은 프로그래머의 실수일 가능성이 높습니다."
            //        },
            //        "0024_NOCHKERR": {
            //          "id": "0024_NOCHKERR",
            //          "name": "부적절한 예외 처리",
            //          "cwe": "CWE-754",
            //          "risky": 4,
            //          "description": "프로그램 수행 중에 함수의 결괏값에 대한 적절한 처리 또는 예외 상황에 대한 조건을 적절하게 검사하지 않을 경우, 예기치 않은 문제를 야기할 수 있습니다."
            //        },
            //        "002C_RETPRIVA": {
            //          "id": "002C_RETPRIVA",
            //          "name": "Public 메소드로부터 반환된 Private 배열",
            //          "cwe": "CWE-495",
            //          "risky": 3,
            //          "description": "private로 선언된 배열을 public으로 선언된 메소드로 반환(return)하면, 그 배열의 레퍼런스가 외부에 공개되어 외부에서 배열수정과 객체 속성변경이 가능해집니다."
            //        },
            //        "0018_RANDOM": {
            //          "id": "0018_RANDOM",
            //          "name": "적절하지 않은 난수 값 사용",
            //          "cwe": "CWE-330",
            //          "risky": 5,
            //          "description": "예측 가능한 난수를 사용하는 것은 시스템에 보안 약점을 야기합니다. 예측 불가능한 숫자가 필요한 상황에서 예측 가능한 난수를 사용한다면, 공격자는 SW에서 생성되는 다음 숫자를 예상하여 시스템을 공격하는 것이 가능합니다."
            //        },
            //        "000F_FORMATI": {
            //          "id": "000F_FORMATI",
            //          "name": "포맷 스트링 삽입",
            //          "cwe": "CWE-134",
            //          "risky": 5,
            //          "description": "외부로부터 입력된 값을 검증하지 않고 입·출력 함수의 포맷 문자열로 그대로 사용하는 경우 발생할 수 있는 보안 약점입니다. 공격자는 포맷 문자열을 이용하여 취약한 프로세스를 공격하거나 메모리 내용을 읽거나 쓸 수 있습니다. 그 결과, 공격자는 취약한 프로세스의 권한을 취득하여 임의의 코드를 실행할 수 있습니다. \r\n"
            //        }
            //      },
            //      "warns": [
            //        {
            //          "id": 1445669,
            //          "file": "D:/workspace/murph/Cooper/target/package/CooperData/ProjectRepos/kr.codemind.lab/src/main/java/com/thealgorithms/maths/PrimeCheck.java",
            //          "canon": "0018_RANDOM",
            //          "traces": {
            //            "0": {
            //              "file": "D:/workspace/murph/Cooper/target/package/CooperData/ProjectRepos/kr.codemind.lab/src/main/java/com/thealgorithms/maths/PrimeCheck.java",
            //              "sLine": 63,
            //              "sColumn": 34,
            //              "eLine": 63,
            //              "eColumn": 47
            //            }
            //          },
            //          "lang": "Java",
            //          "status": "detection",
            //          "requester": null,
            //          "sink": {
            //            "file": "D:/workspace/murph/Cooper/target/package/CooperData/ProjectRepos/kr.codemind.lab/src/main/java/com/thealgorithms/maths/PrimeCheck.java",
            //            "sLine": 63,
            //            "sColumn": 34,
            //            "eLine": 63,
            //            "eColumn": 47
            //          }
            //        },
            //        {
            //          "id": 1445631,
            //          "file": "D:/workspace/murph/Cooper/target/package/CooperData/ProjectRepos/kr.codemind.lab/src/main/java/com/thealgorithms/datastructures/trees/AVLTree.java",
            //          "canon": "005B_TRUE",
            //          "traces": {
            //            "0": {
            //              "file": "D:/workspace/murph/Cooper/target/package/CooperData/ProjectRepos/kr.codemind.lab/src/main/java/com/thealgorithms/datastructures/trees/AVLTree.java",
            //              "sLine": 26,
            //              "sColumn": 19,
            //              "eLine": 26,
            //              "eColumn": 23
            //            }
            //          },
            //          "lang": "Java",
            //          "status": "detection",
            //          "requester": null,
            //          "sink": {
            //            "file": "D:/workspace/murph/Cooper/target/package/CooperData/ProjectRepos/kr.codemind.lab/src/main/java/com/thealgorithms/datastructures/trees/AVLTree.java",
            //            "sLine": 26,
            //            "sColumn": 19,
            //            "eLine": 26,
            //            "eColumn": 23
            //          }
            //        }
            //      ]
            //    }
        } else {
            throw new InvalidParameterException(content);
        }
    }

    /**
     * 프로젝트 분석 결과 조회
     * @param projectName
     * @param sequence
     */
    private static void loadAnalysisResultRuleStatistics(String projectName, int sequence) throws IOException {
        String url = CODEMIND_URL + "/api/" + projectName + "/" + sequence + "/analysis-result-rule-statistics";
        System.out.println("URL: " + url);
        HttpGet get = new HttpGet(url);
        CloseableHttpResponse response = httpclient.execute(get);
        String content = new BasicResponseHandler().handleEntity(response.getEntity());
        if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
            JSONObject json = new JSONObject(content);
            // 조회된 결과에는 아래와 같이 분석결과 요약정보 및 각 파일에 상세 취약점 정보가 포함됩니다.
            // warns: [] - 취약점 목록, files: {} - 파일 목록, canons: [] - 규칙 목록
            System.out.println("------------------------------------------------------------------------------");
            System.out.println("project: " + json.getString("project_name") +
                    ", totalLines: " + json.getInt("total_lines") +
                    ", files: " + json.getInt("total_files") +
                    ", canons: " + json.getJSONArray("canons").length() +
                    ", startTime: " + json.getInt("start_time") +
                    ", endTime: " + json.getInt("end_time"));
        } else {
            throw new InvalidParameterException(content);
        }
    }

    /**
     * /user/login/check
     */
    private static void checkLogin() throws IOException {
        CloseableHttpResponse response = null;
        try {
            int count = 0;
            HttpGet get = new HttpGet(CODEMIND_URL + "/user/login/check");
            get.setHeader("Referer", "http://127.0.0.1:8080/user/login/process");       // value 값에 아무 URL을 전달하면 됩니다.
            System.out.println("Referer: " + get.getFirstHeader("Referer"));
            response = httpclient.execute(get);
            String content = new BasicResponseHandler().handleEntity(response.getEntity());
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                JSONObject json = new JSONObject(content);
                csrf = json.getString("_csrf");
                System.out.println("loginCheck Response: " + content);
            } else {
                throw new InvalidParameterException(content);
            }
        } catch (Exception e) {
            System.out.println("checkLogin: " + e.getMessage());
        } finally {
            if(response != null) response.close();
        }
    }

    private static void addProject() throws IOException {
        HttpPost post = new HttpPost(CODEMIND_URL + "/api/project/create");
        CloseableHttpResponse response = null;
        try {
            List<NameValuePair> entity = new ArrayList();
            entity.add(new BasicNameValuePair("name", "PJ00001"));
            entity.add(new BasicNameValuePair("title", "sample project by RestSample"));
            entity.add(new BasicNameValuePair("repotype", "git"));
            entity.add(new BasicNameValuePair("repopath", "https://github.com/TheAlgorithms/Java.git"));
            entity.add(new BasicNameValuePair("branch", "master"));
            entity.add(new BasicNameValuePair("repoid", ""));
            entity.add(new BasicNameValuePair("repopw", ""));
            entity.add(new BasicNameValuePair("buildEnvId", "2"));
            entity.add(new BasicNameValuePair("ruleset_list", "1,2,3"));
            entity.add(new BasicNameValuePair("analtimeout", "0"));
            entity.add(new BasicNameValuePair("equalizer", "100/50/100/100"));
            entity.add(new BasicNameValuePair("_csrf", csrf));
            post.setEntity(new UrlEncodedFormEntity(entity));
            post.setHeader("Referer", "http://10.0.0.23:8080");
            response = httpclient.execute(post);
            String content = new BasicResponseHandler().handleEntity(response.getEntity());
            if( response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                System.out.println("project create failed: " + content);
            }
            else {
                System.out.println("project created ok...");
            }
        } finally {
            if(response != null) response.close();
        }
    }

    private static void updateProject() throws IOException {
        HttpPut put = new HttpPut(CODEMIND_URL + "/api/project/PJ00001/update");
        CloseableHttpResponse response = null;
        try {
            // 삼성화재 요청한 parameters: title, branch, analtimeout, equalizer, _csrf, projectName, buildEnvId, repoType, repoPath, rulesetList
            List<NameValuePair> entity = new ArrayList();
            entity.add(new BasicNameValuePair("title", "sample project modified by RestSample"));
            entity.add(new BasicNameValuePair("repoType", "git"));
            entity.add(new BasicNameValuePair("repoPath", "https://github.com/TheAlgorithms/Java.git"));
            entity.add(new BasicNameValuePair("branch", "master"));
            entity.add(new BasicNameValuePair("buildEnvId", "2"));
            entity.add(new BasicNameValuePair("rulesetList", "1,2,3"));
            put.setEntity(new UrlEncodedFormEntity(entity));
            response = httpclient.execute(put);
            String content = new BasicResponseHandler().handleEntity(response.getEntity());
            if( response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                System.out.println("project update failed: " + content);
            }
            else {
                System.out.println("project update ok...");
            }
        } finally {
            if(response != null) response.close();
        }
    }

    private static void deleteProject() throws IOException {
        CloseableHttpResponse response = null;
        try {
            int count = 0;
            HttpGet get = new HttpGet(CODEMIND_URL + "/api/project/PJ00001/delete");
            response = httpclient.execute(get);
            String content = new BasicResponseHandler().handleEntity(response.getEntity());
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                System.out.println("project delete ok...");
            } else {
                System.out.println("project delete failed: " + content);
            }
        } finally {
            if(response != null) response.close();
        }
    }
}


