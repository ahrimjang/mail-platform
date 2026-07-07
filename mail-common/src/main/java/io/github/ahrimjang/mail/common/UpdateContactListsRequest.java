package io.github.ahrimjang.mail.common;

import java.util.List;

/**
 * Request to replace the set of lists a contact belongs to.
 *
 * @param listIds the full new set of list ids for the contact
 */
public record UpdateContactListsRequest(
        List<Long> listIds
) {
}
