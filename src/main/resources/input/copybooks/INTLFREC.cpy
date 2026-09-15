       01 INTEGRAL-LIFE-POLICY.
          05 ILP-POLICY-NUMBER          PIC X(10).
          05 ILP-PRODUCT-CODE           PIC X(04).
             88 ILP-WHOLE-LIFE              VALUE 'ILWL'.
             88 ILP-TERM-LIFE               VALUE 'ILTL'.
             88 ILP-UNIVERSAL-LIFE          VALUE 'ILUL'.
             88 ILP-ENDOWMENT               VALUE 'ILEN'.
          05 ILP-CUSTOMER-NUMBER        PIC X(08).
          05 ILP-ISSUE-DATE             PIC 9(08).
          05 ILP-MATURITY-DATE          PIC 9(08).
          05 ILP-FACE-AMOUNT            PIC 9(09)V99 COMP-3.
          05 ILP-CASH-VALUE             PIC 9(09)V99 COMP-3.
          05 ILP-SURRENDER-VALUE        PIC 9(09)V99 COMP-3.
          05 ILP-DEATH-BENEFIT          PIC 9(09)V99 COMP-3.
          05 ILP-ANNUAL-PREMIUM         PIC 9(07)V99 COMP-3.
          05 ILP-PREMIUM-PAID-TO-DATE   PIC 9(09)V99 COMP-3.
          05 ILP-DIVIDENDS-ACCUMULATED  PIC 9(07)V99 COMP-3.
          05 ILP-LOAN-BALANCE           PIC 9(07)V99 COMP-3.
          05 ILP-LOAN-INTEREST-RATE     PIC 9(02)V99 COMP-3.
          05 ILP-POLICY-STATUS          PIC X(01).
             88 ILP-INFORCE                 VALUE 'I'.
             88 ILP-PAID-UP                 VALUE 'P'.
             88 ILP-EXTENDED-TERM           VALUE 'E'.
             88 ILP-SURRENDERED             VALUE 'S'.
             88 ILP-MATURED                 VALUE 'M'.
             88 ILP-LAPSED                  VALUE 'L'.
          05 ILP-PREMIUM-STATUS         PIC X(01).
             88 ILP-PREMIUM-CURRENT         VALUE 'C'.
             88 ILP-PREMIUM-GRACE           VALUE 'G'.
             88 ILP-PREMIUM-WAIVED          VALUE 'W'.
          05 ILP-WAIVER-OF-PREMIUM      PIC X(01).
             88 ILP-WAIVER-RIDER-YES        VALUE 'Y'.
             88 ILP-WAIVER-RIDER-NO         VALUE 'N'.
          05 ILP-ACCIDENTAL-DEATH       PIC X(01).
             88 ILP-AD-RIDER-YES            VALUE 'Y'.
             88 ILP-AD-RIDER-NO             VALUE 'N'.
          05 ILP-ISSUE-AGE              PIC 9(03) COMP-3.
          05 ILP-ATTAINED-AGE           PIC 9(03) COMP-3.
          05 ILP-RISK-CLASS             PIC X(02).
             88 ILP-PREFERRED-PLUS          VALUE 'PP'.
             88 ILP-PREFERRED               VALUE 'PR'.
             88 ILP-STANDARD                VALUE 'ST'.
             88 ILP-SUBSTANDARD             VALUE 'SS'.
             88 ILP-RATED                   VALUE 'RT'.
          05 ILP-LAST-PREMIUM-DATE      PIC 9(08).
          05 ILP-NEXT-PREMIUM-DATE      PIC 9(08).
          05 ILP-YEARS-IN-FORCE         PIC 9(03) COMP-3.
          05 ILP-TABLE-RATING           PIC 9(03) COMP-3 VALUE ZEROS.
          05 ILP-FLAT-EXTRA             PIC 9(05)V99 COMP-3 VALUE ZEROS.
          05 ILP-AGENT-CODE             PIC X(06).
          05 ILP-FILLER                 PIC X(08).

       01 INTEGRAL-LIFE-RESPONSE.
          05 ILR-RETURN-CODE            PIC 9(04).
             88 ILR-SUCCESS                 VALUE 0000.
             88 ILR-POLICY-NOT-FOUND        VALUE 0001.
             88 ILR-POLICY-INACTIVE         VALUE 0002.
             88 ILR-LOAN-EXCEEDS-VALUE      VALUE 0010.
             88 ILR-SURRENDER-NOT-ALLOWED   VALUE 0011.
             88 ILR-INVALID-REQUEST         VALUE 0099.
          05 ILR-MESSAGE                PIC X(80).
          05 ILR-CALCULATED-VALUE       PIC 9(09)V99 COMP-3.
          05 ILR-EFFECTIVE-DATE         PIC 9(08).
          05 ILR-FILLER                 PIC X(16).