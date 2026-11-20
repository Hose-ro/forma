package com.olvenio.checkout.api;

import com.olvenio.shared.address.PostalDirectory;
import com.olvenio.shared.domain.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PostalCodeController {
    private final PostalDirectory directory;

    public PostalCodeController(PostalDirectory directory) { this.directory = directory; }

    @GetMapping("/api/v1/checkout/postal-codes/{code}")
    public PostalDirectory.PostalCode postalCode(@PathVariable String code) {
        var result = directory.lookup(code);
        if (result == null) throw new NotFoundException("No encontramos ese código postal.");
        return result;
    }
}
