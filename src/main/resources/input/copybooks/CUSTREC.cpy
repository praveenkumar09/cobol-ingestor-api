        01 CUSTOMER-RECORD.
          05 CST-CUSTOMER-NUMBER        PIC X(08).
          05 CST-FIRST-NAME             PIC X(20).
          05 CST-LAST-NAME              PIC X(25).
          05 CST-MIDDLE-INITIAL         PIC X(01).
          05 CST-DATE-OF-BIRTH          PIC 9(08).
          05 CST-SSN                    PIC X(09).
          05 CST-GENDER                 PIC X(01).
             88 CST-MALE                    VALUE 'M'.
             88 CST-FEMALE                  VALUE 'F'.
          05 CST-MARITAL-STATUS         PIC X(01).
             88 CST-SINGLE                  VALUE 'S'.
             88 CST-MARRIED                 VALUE 'M'.
             88 CST-DIVORCED               VALUE 'D'.
             88 CST-WIDOWED                 VALUE 'W'.
          05 CST-ADDRESS.
             10 CST-STREET-ADDRESS      PIC X(40).
             10 CST-CITY                PIC X(20).
             10 CST-STATE               PIC X(02).
             10 CST-ZIP-CODE            PIC X(10).
          05 CST-PHONE-HOME             PIC X(15).
          05 CST-PHONE-WORK             PIC X(15).
          05 CST-EMAIL-ADDRESS          PIC X(50).
          05 CST-CUSTOMER-SINCE         PIC 9(08).
          05 CST-RISK-CATEGORY          PIC X(01).
             88 CST-LOW-RISK                VALUE 'L'.
             88 CST-MEDIUM-RISK             VALUE 'M'.
             88 CST-HIGH-RISK               VALUE 'H'.
          05 CST-CREDIT-SCORE           PIC 9(03) COMP-3.
          05 CST-OCCUPATION-CODE        PIC X(06).
          05 CST-ANNUAL-INCOME          PIC 9(09)V99 COMP-3.
          05 CST-ACTIVE-POLICIES        PIC 9(03) COMP-3.
          05 CST-PRIOR-CLAIMS-COUNT     PIC 9(03) COMP-3.
          05 CST-CUSTOMER-STATUS        PIC X(01).
             88 CST-ACTIVE                  VALUE 'A'.
             88 CST-INACTIVE                VALUE 'I'.
             88 CST-DECEASED                VALUE 'D'.
          05 CST-PREFERRED-LANGUAGE     PIC X(03).
          05 CST-FILLER                 PIC X(10).