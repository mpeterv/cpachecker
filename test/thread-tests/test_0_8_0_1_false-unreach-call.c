#include "pthread_test.h"

pthread_mutex_t m;
int res = 0;

struct data0_t
{
    int b;
    void (*func)();
};

struct data_t
{
    int a;
    struct data0_t dt;
} data;

int thread_data1 = 1;
int thread_data2 = 2;

pthread_t thread1;
pthread_t thread2;

void
true_func()
{
    ldv_mutex_model_lock(&m, NULL);
    res = res + 1;
    ldv_mutex_model_unlock(&m, NULL);
}

void
false_func()
{
    res = res + 1;
}

void *
thread_func(void *thread_data)
{
    data.dt.func();

	pthread_exit(0);
}

int main()
{
    data.dt.func = false_func;

	pthread_create(&thread1, NULL, thread_func, (void *) &thread_data1);
	pthread_create(&thread2, NULL, thread_func, (void *) &thread_data2);

	pthread_join(thread1, NULL);
	pthread_join(thread2, NULL);

    int out = res;

	return 0;
}
