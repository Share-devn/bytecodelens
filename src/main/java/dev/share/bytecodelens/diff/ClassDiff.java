package dev.share.bytecodelens.diff;

import java.util.List;

public record ClassDiff(
        ChangeType change,
        String classFqn,
        String packageName,
        String simpleName,
        byte[] bytesA,
        byte[] bytesB,
        List<MemberDiff> methods,
        List<MemberDiff> fields,
        List<String> headerChanges
) {
}
