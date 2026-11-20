package com.olvenio.shared.address;

import com.olvenio.shared.domain.InvalidRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostalDirectoryTest {
    private final PostalDirectory directory = spy(new PostalDirectory(RestClient.builder()));

    @Test void validatesTheCodeAndItsSettlementEvenWhenTheCityHasOtherColonias() {
        var code = new PostalDirectory.PostalCode("06600", "Ciudad de México", "Cuauhtémoc",
                List.of(new PostalDirectory.Neighborhood("Juárez", "Colonia", "Ciudad de México")));
        doReturn(code).when(directory).lookup("06600");

        assertEquals(code, directory.validate("06600", "CDMX", "Cuauhtemoc", "Juarez"));
        assertEquals(code, directory.validate("06600", "Ciudad de México", "Ciudad de México", null));
        assertThrows(InvalidRequestException.class,
                () -> directory.validate("06600", "Ciudad de México", "Cuauhtémoc", "Roma Norte"));
        assertThrows(InvalidRequestException.class,
                () -> directory.validate("06600", "Estado de México", "Cuauhtémoc", "Juárez"));
        assertThrows(InvalidRequestException.class,
                () -> directory.validate("06600", "Ciudad de México", "Benito Juárez", "Juárez"));
    }

    @Test void rejectsMissingCodesAndKeepsLeadingZeros() {
        doReturn(null).when(directory).lookup("00000");
        assertThrows(InvalidRequestException.class, () -> directory.validate("00000", "Jalisco", "Zapopan", null));
        assertThrows(InvalidRequestException.class, () -> directory.lookup("6600"));
    }
}
