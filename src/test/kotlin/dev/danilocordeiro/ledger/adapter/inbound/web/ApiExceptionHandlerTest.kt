package dev.danilocordeiro.ledger.adapter.inbound.web

import dev.danilocordeiro.ledger.application.port.input.PostTransactionUseCase
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ApiExceptionHandlerTest {

    private val useCase = mockk<PostTransactionUseCase>()

    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(PostTransactionController(useCase))
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    private val validBody = """
        {"currency":"BRL","entries":[
          {"accountId":"00000000-0000-0000-0000-000000000001","direction":"DEBIT","amountMinorUnits":100},
          {"accountId":"00000000-0000-0000-0000-000000000002","direction":"CREDIT","amountMinorUnits":100}
        ]}
    """.trimIndent()

    @Test
    fun `a posting without the Idempotency-Key header is rejected with 400 and a problem detail`() {
        mvc.perform(post("/v1/transactions").contentType(MediaType.APPLICATION_JSON).content(validBody))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
    }

    @Test
    fun `a posting with a malformed JSON body is rejected with 400`() {
        mvc.perform(
            post("/v1/transactions")
                .header("Idempotency-Key", "key-12345678")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":"),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `a GET on the transactions collection is rejected with 405`() {
        mvc.perform(get("/v1/transactions")).andExpect(status().isMethodNotAllowed)
    }

    @Test
    fun `an unexpected failure still returns 500 with a generic body`() {
        every { useCase.post(any()) } throws IllegalStateException("db password is hunter2")

        mvc.perform(
            post("/v1/transactions")
                .header("Idempotency-Key", "key-12345678")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody),
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
            .andExpect(jsonPath("$.title").value("Internal error"))
    }
}
