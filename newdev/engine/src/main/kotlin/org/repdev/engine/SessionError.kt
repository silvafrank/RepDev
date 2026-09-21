package org.repdev.engine

/**
 * Ported from com.repdev.SessionError. Same error codes, same message text —
 * verified word-for-word against the original switch statement.
 *
 * `showError()` did NOT come along: it opened an SWT MessageBox directly from
 * the enum, which is exactly the UI-coupling this engine module exists to
 * avoid. Presenting the error is now the caller's job — catch a SessionError,
 * read `.message`, show it however the current UI (Compose, a test harness,
 * whatever) shows errors.
 */
enum class SessionError(val message: String) {
    NONE("No Session Error"),
    SERVER_NOT_FOUND("Server not found, please check network connections"),
    AIX_LOGIN_WRONG("AIX Login information is incorrect!"),
    SYM_INVALID("Specified SYM is invalid."),
    USERID_INVALID("Invalid User ID/Password"),
    USERID_PASSWORD_CHANGE("User Password change required."),
    ALREADY_CONNECTED("Symitar Session is already connected."),
    NOT_CONNECTED("Symitar Session is not connected!"),
    IO_ERROR("I/O Error."),
    CONSOLE_BLOCKED("This console has been blocked!"),
    INVALID_FILE_TYPE("Invalid File Type."),
    INVALID_QUEUE("Invalid Queue."),
    INPUT_ERROR("Input Error was detected."),
    IP_NOT_ALLOWED("Logins not allowed from host."),
    FILENAME_TOO_LONG("Filename is too long!"),
    ARGUMENT_ERROR("Invalid File Argument!"),
    NULL_POINTER("Null Pointer."),
    PLINK_NOT_FOUND("Plink.exe was not found in the startup directory."),
    NOT_WINDOWSLEVEL_3("WINDOWSLEVEL not set to 3."),
    NOT_WINDOWSLEVEL_3_PASS_WILL_EXPIRE(
        "RepDev is in Symulate mode because your AIX password is due to expire.  Please change it now."
    ),
    INCOMPATIBLE_REVISION("Incompatible Revison"),
    UNDEFINED_ERROR("Undefined Error"),
    AIX_PASSWORD_TO_EXPIRE("AIX password due to expire."),
    AIX_PASSWORD_EXPIRED("AIX password expired."),
    // The old message told the user to go edit a PuTTY registry key — that was
    // PuTTY/plink-specific advice for a jsch+plink hybrid setup that no longer
    // exists once we're solely on Apache MINA SSHD (see DECISIONS.md). Same
    // error condition (host key changed since last connect), updated guidance.
    SSH_KEY_CHANGED(
        "The SSH host key presented by the server has changed since the last successful connection. " +
            "Verify this is an expected change (e.g. the server was rebuilt) before proceeding, then " +
            "remove the stale entry from the known-hosts store in RepDev's config directory."
    ),
    FILE_READ_ONLY("File not saved.  Read Only !!"),
    DBMS_NOT_AVAILABLE("DBMS not available.  Connection refused.");
}
