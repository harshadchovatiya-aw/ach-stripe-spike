package com.anywhere.spike.achstripe.spike;

import com.anywhere.spike.achstripe.jbilling.core.JbUser;
import com.anywhere.spike.achstripe.jbilling.core.JbUserRepository;
import java.math.BigDecimal;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Seeds one AnswerForce test account so the UI has something to work with. */
@Component
public class DataSeeder implements ApplicationRunner {

    static final String ANSWERFORCE_BRAND_ID = "c26495e9-f41e-4b8d-a442-22ab742563b7";

    private final JbUserRepository userRepository;

    public DataSeeder(JbUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() == 0) {
            userRepository.save(new JbUser(
                    "AF-100001",
                    ANSWERFORCE_BRAND_ID,
                    "jane.doe@example.com",
                    "Jane",
                    "Doe",
                    "10001",
                    "US",
                    new BigDecimal("49.00")));
        }
    }
}
