n       01 ERROR-CONTROL.
          05 ERR-RETURN-CODE            PIC 9(04) VALUE ZEROS.
             88 ERR-SUCCESS                 VALUE 0000.
             88 ERR-RECORD-NOT-FOUND        VALUE 0001.
             88 ERR-DUPLICATE-RECORD        VALUE 0002.
             88 ERR-INVALID-POLICY          VALUE 0010.
             88 ERR-POLICY-LAPSED           VALUE 0011.
             88 ERR-POLICY-CANCELLED        VALUE 0012.
             88 ERR-POLICY-EXPIRED          VALUE 0013.
             88 ERR-INVALID-CUSTOMER        VALUE 0020.
             88 ERR-CUSTOMER-INACTIVE       VALUE 0021.
             88 ERR-CUSTOMER-DECEASED       VALUE 0022.
             88 ERR-INVALID-CLAIM           VALUE 0030.
             88 ERR-CLAIM-LIMIT-EXCEEDED    VALUE 0031.
             88 ERR-CLAIM-ALREADY-PAID      VALUE 0032.
             88 ERR-INVALID-AMOUNT          VALUE 0040.
             88 ERR-AMOUNT-EXCEEDS-COVERAGE VALUE 0041.
             88 ERR-INVALID-DATE            VALUE 0050.
             88 ERR-DATE-OUT-OF-RANGE       VALUE 0051.
             88 ERR-FILE-OPEN-FAILED        VALUE 0090.
             88 ERR-FILE-READ-FAILED        VALUE 0091.
             88 ERR-FILE-WRITE-FAILED       VALUE 0092.
             88 ERR-SYSTEM-ERROR            VALUE 9999.
          05 ERR-MESSAGE-TEXT           PIC X(80) VALUE SPACES.
          05 ERR-PROGRAM-NAME           PIC X(10) VALUE SPACES.
          05 ERR-TIMESTAMP              PIC X(26) VALUE SPACES.
          05 ERR-SQL-CODE               PIC S9(09) COMP-3 VALUE ZEROS.

       01 PROGRAM-CONSTANTS.
          05 PC-MAX-CLAIM-AMOUNT        PIC 9(09)V99 COMP-3
                                            VALUE 999999999.99.
          05 PC-MIN-PREMIUM-RATE        PIC 9(03)V9(4) COMP-3
                                            VALUE 0.0025.
          05 PC-MAX-PREMIUM-RATE        PIC 9(03)V9(4) COMP-3
                                            VALUE 0.9500.
          05 PC-RENEWAL-GRACE-DAYS      PIC 9(03) COMP-3 VALUE 030.
          05 PC-CLAIM-PROCESS-DAYS      PIC 9(03) COMP-3 VALUE 045.
          05 PC-GOOD-DRIVER-DISCOUNT    PIC 9(03)V99 COMP-3 VALUE 005.00.
          05 PC-MULTI-POLICY-DISCOUNT   PIC 9(03)V99 COMP-3 VALUE 003.00.
          05 PC-LOYALTY-DISCOUNT-YEARS  PIC 9(02) COMP-3 VALUE 05.
          05 PC-AUTO-APPROVE-LIMIT      PIC 9(07)V99 COMP-3 VALUE 500.00.
          05 PC-CURRENT-DATE            PIC 9(08) VALUE ZEROS.
          05 PC-SYSTEM-NAME             PIC X(20) VALUE 'INS-AS400-PROD'.
          05 PC-VERSION                 PIC X(08) VALUE 'V3.2.1  '.