package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import java.util.List;

/** The shields granted or declared. */
public interface ShieldRepository {

    List<Shield> byMember(String memberId);

    void add(Shield entry);
}
