void
func0(void)
{
    return;
}

void
func1(void)
{
    ERROR: goto ERROR;
}

struct str
{
    int a;
    void (*fptr)(void);
};

int
main(int argc, char **argv)
{
    struct str *st;
    struct str st2;
    struct str st3;
    st->a = 1;
    st->fptr = func1;
    st2.fptr = func1;
    st3 = st2;
    st->fptr();
    st2.fptr();
    func1();

    return 0;
}

