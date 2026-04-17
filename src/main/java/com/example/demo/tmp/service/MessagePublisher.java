package com.example.demo.tmp.service;

import lombok.extern.slf4j.Slf4j;
import org.fusesource.hawtbuf.UTF8Buffer;
import org.fusesource.mqtt.client.FutureConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.QoS;
import org.jolokia.client.J4pClient;
import org.jolokia.client.request.J4pReadRequest;
import org.jolokia.client.request.J4pReadResponse;
import org.jolokia.client.request.J4pSearchRequest;
import org.jolokia.client.request.J4pSearchResponse;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class MessagePublisher {

    public void send(){

        List<Integer> numbers = IntStream.rangeClosed(1, 3)
                .boxed()
                .collect(Collectors.toList());

        numbers.stream().parallel().forEach(number -> {this.publish("qq");});


    }

    public void checkAlive(){

        // === 설정값 (환경에 맞게 수정) ===
        String jolokiaUrl      = "http://localhost:8161/api/jolokia";  // ActiveMQ Jolokia URL
        String jolokiaUser     = "admin";
        String jolokiaPassword = "admin";
        String clientId        = "6215051013";  // 확인할 POS fixedCode digits (또는 fixedCode_sessionId)

        // === 병목 포인트: POS N개면 이 블록이 N번 병렬 실행됨 ===
        long start = System.currentTimeMillis();

        // 매 호출마다 HttpClient 신규 생성 (커넥션 풀 없음 — 병목 원인 1)
        CredentialsProvider provider = new BasicCredentialsProvider();
        provider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(jolokiaUser, jolokiaPassword));
        HttpClient httpClient = HttpClientBuilder.create()
                .setDefaultCredentialsProvider(provider)
                .build();

        J4pClient client = new J4pClient(jolokiaUrl, httpClient);

        // 병목 원인 2: 브로커 전체 connection 목록을 매번 풀스캔
        J4pSearchRequest searchReq = new J4pSearchRequest(
                "org.apache.activemq:connector=clientConnectors,connectorName=auto+nio,type=Broker,*"
        );
        J4pSearchResponse searchResp = client.execute(searchReq);
        JSONArray array = searchResp.getValue();

        System.out.println("전체 connection 수: " + array.size() + "  (조회시간: " + (System.currentTimeMillis() - start) + "ms)");

        if (array.isEmpty()) {
            System.out.println("결과: false (연결 없음)");
            return;
        }

        boolean alive = false;
        Pattern pattern = Pattern.compile(
                "^(.*),(connectionName=modnpay_([a-z0-9]*)_" + clientId + "),(.*)$"
        );

        for (Object entry : array) {
            Matcher matcher = pattern.matcher(entry.toString());
            if (!matcher.matches()) continue;

            // 병목 원인 3: 매칭된 connection마다 추가 Read 요청 발생
            long readStart = System.currentTimeMillis();
            J4pReadResponse readResp = client.execute(new J4pReadRequest(entry.toString()));
            boolean active    = (boolean) readResp.getValue("Active");
            boolean connected = (boolean) readResp.getValue("Connected");
            System.out.println("  Read 요청: " + entry + "  Active=" + active + " Connected=" + connected
                    + "  (" + (System.currentTimeMillis() - readStart) + "ms)");

            if (active && connected) {
                alive = true;
                break;
            }
        }

        System.out.println("최종 alive=" + alive + "  총 소요: " + (System.currentTimeMillis() - start) + "ms");

    }

    public void publish(String message) {

        try{

            MQTT mqtt = new MQTT();

            FutureConnection futureConnection = mqtt.futureConnection();

            try {
                futureConnection.connect().await(10, TimeUnit.SECONDS);

                futureConnection.publish(new UTF8Buffer("topic"), new UTF8Buffer("payload"), QoS.EXACTLY_ONCE, true);

            } finally {
                if (futureConnection != null && futureConnection.isConnected()) {
                    futureConnection.disconnect().await(3, TimeUnit.SECONDS);
                    log.debug("[mqtt] disconnected.");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("fail send mqtt.", e);
        }



    }
}
