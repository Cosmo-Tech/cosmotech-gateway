// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway.controllers

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
class GatewayController {

    @GetMapping(value = ["/about"])
    fun about(): Mono<String> {
        return Mono.just("this is an about")
    }
}