#include "pthread_test.h"

pthread_mutex_t m;
int res = 0;

void *
true_thread_func(void *thread_data)
{
    ldv_mutex_model_lock(&m, NULL);
    res = res + 1;
    ldv_mutex_model_unlock(&m, NULL);
	pthread_exit(0);
}

void *
false_thread_func(void *thread_data)
{
    res = res + 1;
	pthread_exit(0);
}

int main()
{
	void *thread_data1 = NULL;
	void *thread_data2 = NULL;
	pthread_t thread1;
	pthread_t thread2;

    int a = 0;
    void *(*thread_func)(void *);
    if (a < 1)
		thread_func = true_thread_func;
	else
		thread_func = false_thread_func;

	pthread_create(&thread1, NULL, thread_func, thread_data1);
	pthread_create(&thread2, NULL, thread_func, thread_data2);

	pthread_join(thread1, NULL);
	pthread_join(thread2, NULL);

    int out = res;

	return 0;
}
