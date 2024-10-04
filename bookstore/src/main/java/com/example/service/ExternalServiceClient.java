import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class ExternalServiceClient {

  private final RestTemplate restTemplate;
  private final KafkaTemplate<String, String> kafkaTemplate;

  @Autowired
  public ExternalServiceClient(RestTemplate restTemplate, KafkaTemplate<String, String> kafkaTemplate) {
    this.restTemplate = restTemplate;
    this.kafkaTemplate = kafkaTemplate;
  }

  public String callExternalService(String endpoint) {
    return restTemplate.getForObject(endpoint, String.class);
  }

  public void produceKafkaMessage(String topic, String message) {
    kafkaTemplate.send(topic, message);
  }

  @KafkaListener(topics = "input-topic")
  public void consumeKafkaMessage(String message) {
    System.out.println("Received message: " + message);
    // Process the message as needed
  }
}